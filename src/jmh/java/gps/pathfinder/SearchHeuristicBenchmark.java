package gps.pathfinder;

import java.util.concurrent.TimeUnit;
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

/**
 * Microbenchmark for the second pipeline step: turning a built {@link DistanceField} into a
 * per-search {@link SearchHeuristic}. This is the cheap part — it just scans the search's usable
 * teleports to compute the floor (cheapest cast + landing distance) — so the benchmark exists mainly
 * to confirm it stays negligible next to the ~170 ms field build and the search.
 * <p>
 * The config load and field build run once per trial in {@link #setup()} and are NOT measured.
 * Run: {@code ./gradlew -Pjmh jmh --args='SearchHeuristicBenchmark'}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SearchHeuristicBenchmark
{
	@Param({"lumbridge-barrows", "ge-shilo", "capture"})
	public String scenario;

	private PathfinderConfig config;
	private DistanceField field;

	@Setup(Level.Trial)
	public void setup()
	{
		config = BenchScenarios.everythingConfig();
		field = DistanceField.build(config, BenchScenarios.targets(scenario, config));
	}

	@Benchmark
	public SearchHeuristic buildHeuristic()
	{
		return SearchHeuristic.buildWithField(config, field);
	}
}
