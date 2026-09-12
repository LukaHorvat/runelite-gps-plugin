package gps.pathfinder;

import java.util.Set;
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
 * Microbenchmark for the third pipeline step: one {@link Pathfinder} search from start to targets.
 * The {@code mode} axis contrasts the production A* (with the distance-field heuristic) against the
 * uninformed search (heuristic = null), so the numbers show what the heuristic actually buys per
 * scenario.
 * <p>
 * The config, and the field + heuristic for the A* mode, are built once per trial in {@link #setup()}
 * and are NOT measured. Each invocation constructs a fresh Pathfinder (the search state is single-use)
 * and runs it — that construction is part of a real per-route cost, so it is measured too. Returning
 * the result lets JMH consume it.
 * <p>
 * Run: {@code ./gradlew -Pjmh jmh --args='PathfinderBenchmark'}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PathfinderBenchmark
{
	@Param({"lumbridge-barrows", "ge-shilo", "capture", "island", "deep-wild", "wilderness-escape"})
	public String scenario;

	@Param({"astar", "uninformed"})
	public String mode;

	private PathfinderConfig config;
	private int start;
	private Set<Integer> targets;
	private SearchHeuristic heuristic;

	@Setup(Level.Trial)
	public void setup()
	{
		config = BenchScenarios.everythingConfig();
		start = BenchScenarios.start(scenario);
		targets = BenchScenarios.targets(scenario);
		heuristic = "astar".equals(mode)
			? SearchHeuristic.buildWithField(config, DistanceField.build(config, targets))
			: null;
	}

	@Benchmark
	public PathfinderResult search()
	{
		Pathfinder pathfinder = new Pathfinder(config, start, targets, Integer.MAX_VALUE, heuristic);
		pathfinder.run();
		return pathfinder.getResult();
	}
}
