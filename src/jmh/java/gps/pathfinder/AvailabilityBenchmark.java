package gps.pathfinder;

import java.util.Collections;
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

/**
 * Microbenchmark for the method-availability computation — the work done once per generation on the
 * client thread ({@link PathfinderConfig#refresh}: snapshot game state into the full usable-transport
 * lists and method catalog) and once per search off-thread ({@link
 * PathfinderConfig#rebuildAvailabilityWithExclusions}: re-derive the usable lists for an exclusion
 * set from the base lists, no game-state reads).
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

	@Setup(Level.Trial)
	public void setup()
	{
		config = BenchScenarios.everythingConfig();
	}

	/** The per-generation client-thread refresh: rebuild the full availability from game state. */
	@Benchmark
	public int refresh()
	{
		config.refresh();
		return config.getUsableTeleports(false).length;
	}

	/** The per-search off-thread rebuild from the base lists (here with no exclusions). */
	@Benchmark
	public int rebuildWithoutExclusions()
	{
		config.rebuildAvailabilityWithExclusions(Collections.emptySet());
		return config.getUsableTeleports(false).length;
	}
}
