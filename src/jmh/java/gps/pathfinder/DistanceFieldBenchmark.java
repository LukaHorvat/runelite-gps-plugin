package gps.pathfinder;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
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
import org.openjdk.jmh.annotations.Warmup;
import gps.ShortestPathConfig;
import gps.WorldPointUtil;

/**
 * Microbenchmark for the per-generation distance-field build: one multi-source reverse flood plus
 * the reverse-transport index and the blocked-landing patch. Dev-only — it lives in the jmh source
 * set so it never ships and never counts toward the hub's review surface.
 * <p>
 * The heavy setup (loading the real collision map + transport data and refreshing the everything-mode
 * config) runs once per trial in {@link #setup()} and is NOT measured; only {@link DistanceField#build}
 * is timed. Returning the field lets JMH consume it so the JIT cannot elide the flood.
 * <p>
 * Run e.g.: {@code ./gradlew -Pjmh jmh --args='DistanceFieldBenchmark -f 1 -wi 3 -i 5'}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DistanceFieldBenchmark
{
	// Varrock, and a spread-out three-target set (Varrock / Falador / Draynor) whose wider initial
	// frontier makes the flood do more work — the two ends of what a real generation asks for.
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3213, 3424, 0);
	private static final int FALADOR = WorldPointUtil.packWorldPoint(2965, 3380, 0);
	private static final int DRAYNOR = WorldPointUtil.packWorldPoint(3093, 3245, 0);

	@Param({"single", "multi"})
	public String scenario;

	// 0 = full-map flood; 3 = the production default cost multiple (floor-bounded flood).
	@Param({"0", "3"})
	public int costMultiple;

	private PathfinderConfig config;
	private Set<Integer> targets;

	@Setup(Level.Trial)
	public void setup()
	{
		Client client = Mockito.mock(Client.class);
		ShortestPathConfig cfg = Mockito.mock(ShortestPathConfig.class);
		Mockito.when(cfg.calculationCutoff()).thenReturn(120);
		Mockito.when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		Mockito.when(client.getClientThread()).thenReturn(Thread.currentThread());
		Mockito.when(client.getBoostedSkillLevel(any(Skill.class))).thenReturn(99);

		config = new TestPathfinderConfig(client, cfg).copyForPlanning();
		config.refresh();

		switch (scenario)
		{
			case "single":
				targets = Set.of(VARROCK);
				break;
			case "multi":
				targets = Set.of(VARROCK, FALADOR, DRAYNOR);
				break;
			default:
				throw new IllegalArgumentException("unknown scenario: " + scenario);
		}
	}

	@Benchmark
	public DistanceField build()
	{
		return DistanceField.build(config, targets, costMultiple);
	}
}
