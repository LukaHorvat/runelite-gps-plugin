package gps.pathfinder;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.client.callback.ClientThread;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mockito;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import gps.AlternativeRoutesMode;
import gps.AlternativeRoutesService;
import gps.RouteOption;
import gps.TeleportMethod;

/**
 * Microbenchmark for the true end-to-end "find routes": one {@link AlternativeRoutesService}
 * generation, which builds the distance field once and runs the exclusion-chain + seed searches to
 * produce a page of alternative routes by method. This is the actual cost of a panel "find routes"
 * click, at the plugin's default page size and cost band (the route budget is the same with the
 * panel shown or hidden, see RouteController.routeLimitFor).
 * <p>
 * The generation is asynchronous (own executor + seed pool); the benchmark awaits the terminal
 * {@code done} update on a latch, so the measured wall time is the generation's. The client-thread
 * bounce is run inline via a mock. Output in ms.
 * <p>
 * {@code scenario}: the six single-target queries, then the panel's "nearest bank" (a map-wide
 * target set: no compact field, uninformed searches under the walk cap), "nearest bank and back"
 * (round trips: a return search per route over a start-rooted field), a water pin (sea legs
 * synthesized per generation) and a sealed target (the provably-unreachable short circuit).
 * <p>
 * {@code mode}: {@code owned} is the plugin's default (inventory only, possession checked); the
 * bench client carries nothing, so this is the no-items player, whose flood has no cheap teleport
 * floor and runs widest. {@code everything} bypasses possession: every teleport usable, the widest
 * transport fan-out.
 * <p>
 * {@code cache}: the distance-field cache (plan step N4). A service reuses the field across
 * generations with the same target set and usable transports, so one long-lived service fed the
 * same query in a loop measures a WARM regeneration (the player walking toward a pinned target:
 * one guided search per route, single-digit milliseconds), not a first click. {@code cold} builds
 * a fresh service per invocation, so every generation floods its field (the first click on a new
 * target, where the flood dominates); {@code warm} keeps one service per trial. The shared
 * config's own caches stay warm in both, as they do in the plugin.
 * <p>
 * Run: {@code ./gradlew -Pjmh jmh --args='GenerateBenchmark'}; narrow with
 * {@code -p scenario=capture -p mode=owned -p cache=cold}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class GenerateBenchmark
{
	/** The plugin's defaults: routes per page (ShortestPathConfig.defaultRouteCount) ... */
	private static final int ROUTES_PER_PAGE = 10;
	/** ... and the cost band each search is capped at (RouteSession.DEFAULT_COST_MULTIPLE). */
	private static final int COST_MULTIPLE = 3;

	@Param({"lumbridge-barrows", "ge-shilo", "capture", "island", "deep-wild", "wilderness-escape",
		"nearest-bank", "bank-and-back", "water-pin", "sealed"})
	public String scenario;

	/** owned: the plugin's default mode (inventory, possession checked); everything: all bypassed. */
	@Param({"owned", "everything"})
	public String mode;

	/** cold: a fresh service (empty field cache) per invocation; warm: one service per trial. */
	@Param({"cold", "warm"})
	public String cache;

	private ClientThread clientThread;
	private PathfinderConfig config;
	private AlternativeRoutesService service;
	private int start;
	private Set<Integer> targets;
	private AlternativeRoutesMode routesMode;
	private boolean roundTrip;

	@Setup(Level.Trial)
	public void setup()
	{
		clientThread = Mockito.mock(ClientThread.class);
		Mockito.doAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		}).when(clientThread).invokeLater(any(Runnable.class));

		config = BenchScenarios.everythingConfig();
		start = BenchScenarios.start(scenario);
		targets = BenchScenarios.targets(scenario, config);
		routesMode = "owned".equals(mode) ? AlternativeRoutesMode.OWNED_INVENTORY : AlternativeRoutesMode.ALL_EVERYTHING;
		roundTrip = BenchScenarios.roundTrip(scenario);
		if ("warm".equals(cache))
		{
			service = new AlternativeRoutesService(clientThread, config);
		}
	}

	/** Cold: every invocation starts from an empty field cache, the way a new target does. */
	@Setup(Level.Invocation)
	public void freshServiceWhenCold()
	{
		if ("cold".equals(cache))
		{
			service = new AlternativeRoutesService(clientThread, config);
		}
	}

	@TearDown(Level.Invocation)
	public void dropServiceWhenCold()
	{
		if ("cold".equals(cache))
		{
			service.shutdown();
			service = null;
		}
	}

	@TearDown(Level.Trial)
	public void tearDown()
	{
		if (service != null)
		{
			service.shutdown();
		}
	}

	@Benchmark
	public List<RouteOption> generate() throws InterruptedException
	{
		final CountDownLatch latch = new CountDownLatch(1);
		final AtomicReference<List<RouteOption>> out = new AtomicReference<>();
		service.generate(start, targets, Set.<TeleportMethod>of(), routesMode, ROUTES_PER_PAGE, COST_MULTIPLE,
			roundTrip,
			(routes, catalog, unavailable, done) ->
			{
				if (done)
				{
					out.set(routes);
					latch.countDown();
				}
			});
		latch.await(60, TimeUnit.SECONDS);
		return out.get();
	}
}
