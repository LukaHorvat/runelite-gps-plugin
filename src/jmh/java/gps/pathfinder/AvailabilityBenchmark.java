package gps.pathfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import gps.TeleportMethod;

/**
 * Microbenchmark for the method-availability computation: the work done once per generation on the
 * client thread ({@link PathfinderConfig#refresh}: snapshot game state into the full usable-transport
 * lists and method catalog) and once per search off-thread ({@link
 * PathfinderConfig#rebuildAvailabilityWithExclusions}: re-derive the usable lists for an exclusion
 * set from the base lists, no game-state reads).
 * <p>
 * The rebuild is measured WITH an exclusion, alternating between two catalog methods: with no
 * exclusions and no extras the rebuild hands back the base lists untouched (the first search's
 * fast path), which is a no-op worth nothing on a chart. Every chain iteration after the first
 * excludes something, so the filtered rebuild is the per-search cost.
 * <p>
 * Run: {@code ./gradlew -Pjmh jmh --args='AvailabilityBenchmark'}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AvailabilityBenchmark
{
	private PathfinderConfig config;
	private List<Set<TeleportMethod>> exclusions;
	private int next;

	@Setup(Level.Trial)
	public void setup()
	{
		config = BenchScenarios.everythingConfig();
		List<TeleportMethod> catalog = new ArrayList<>(config.getMethodCatalog());
		if (catalog.size() < 2)
		{
			throw new IllegalStateException("the catalog must offer two methods to exclude");
		}
		exclusions = List.of(Set.of(catalog.get(0)), Set.of(catalog.get(1)));
	}

	/** The per-generation client-thread refresh: rebuild the full availability from game state. */
	@Benchmark
	public int refresh()
	{
		config.refresh();
		return config.getUsableTeleports(false).length;
	}

	/** The per-search off-thread rebuild from the base lists, with one method excluded. */
	@Benchmark
	public int rebuildWithExclusion()
	{
		config.rebuildAvailabilityWithExclusions(exclusions.get(next++ & 1));
		return config.getUsableTeleports(false).length;
	}
}
