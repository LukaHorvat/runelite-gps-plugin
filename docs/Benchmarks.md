# Benchmarks

The routing engine's performance is measured two ways: JMH microbenchmarks under `src/jmh`
(dev-only, never shipped, never on the hub's review surface) and gated probe tests in the test
suite that print timings. A checked-in baseline records what the JMH set measured on the
development machine, so a change can be compared against it.

## Running

The JMH source set only exists with the `-Pjmh` property, which keeps `jmh-core` and the
benchmarks out of the default build:

    ./gradlew -Pjmh jmh --args='GenerateBenchmark'
    ./gradlew -Pjmh jmh --args='GenerateBenchmark -p scenario=capture -p mode=owned -p cache=cold'
    ./gradlew -Pjmh jmh --args='-rf json -rff build/jmh/result.json'

The first runs one benchmark class over every parameter combination; the second narrows the
axes; the third runs the whole set and writes JSON for the comparison below. A full run takes
about twelve minutes. JMH forks its measurement JVM from the JDK Gradle runs on, which on the
development machine is 21 while the client ships on 11. To measure on the production JDK, run
Gradle itself on it:

    ./gradlew -Pjmh jmh "-Dorg.gradle.java.home=C:/Program Files/Eclipse Adoptium/jdk-11.0.31.11-hotspot" --args='GenerateBenchmark'

The baseline below was recorded on 21; compare like with like.

The probes live in the test suite behind system properties and print `BENCH` lines. A run that
forwards any probe flag shows the tests' standard output; scope it with `--tests` or the whole
suite's output comes along:

    ./gradlew test --tests gps.SearchBenchmarkProbeTest -Dgps.searchBench=true
    ./gradlew test --tests gps.NearestSearchProfileTest
    ./gradlew test --tests gps.SeedSearchProfileProbeTest -Dgps.seedProfile=true
    ./gradlew test --tests gps.RefreshProfileProbeTest -Dgps.refreshCost=true

## What each benchmark measures

| Benchmark | Measures | Why it matters |
|---|---|---|
| `GenerateBenchmark` | One full "find routes" generation: refresh, field, chain, seeds, tail diversity, baselines. Axes: `scenario` (ten queries), `mode` (owned is the plugin's default, everything bypasses possession), `cache` (cold is a first click, warm is regenerating toward the same target) | The cost of a click, and of walking toward a pin |
| `DistanceFieldBenchmark` | The reverse flood that builds the distance field: full (multiple 0) and bounded to the cost band (multiple 3) | The single largest cost of a cold generation |
| `PathfinderBenchmark` | One search, guided by the field versus uninformed | What the heuristic buys per scenario |
| `SearchHeuristicBenchmark` | Turning a field into a per-search heuristic | Confirms it stays negligible |
| `AvailabilityBenchmark` | The client-thread refresh, and the per-search rebuild with one method excluded | Client-thread time per generation; per-search overhead |
| `SailingSeaBenchmark` | The wet flood behind a water pin, the sea-track builder per sailing leg | The water-pin hot paths |

The generate scenarios: six single-target queries of increasing difficulty, then the panel's
"nearest bank" (a map-wide target set, so no compact field and every search runs uninformed under
the walk cap), "nearest bank and back" (round trips: a return search per route), a water pin (sea
legs synthesized per generation) and a sealed target (the provably-unreachable short circuit). The
bench client carries nothing, so the owned mode is the no-items player: walking plus the free
networks, the flood with no cheap teleport floor.

## Comparing against the baseline

`docs/benchmarks/baseline.json` is a full run's JMH output; the header of this section says when
and on what. To compare a change:

    ./gradlew -Pjmh jmh --args='-rf json -rff build/jmh/result.json'
    python scripts/bench_compare.py

The script matches every benchmark and parameter combination by name, prints the baseline score,
the new score and the delta, and marks deltas beyond 15 percent (`--threshold`) as SLOWER or
FASTER. `--markdown` prints a table for a journal entry; `--fail-on-slower` exits non-zero when
anything is marked SLOWER. Single-fork JMH runs vary by a few percent between runs on the same
machine, and the sailing benchmarks (which cycle through inputs of different sizes) by more, so
treat single-digit deltas as noise and re-run before believing a marked one. Numbers are not
comparable across machines: re-baseline when the machine changes.

To re-baseline after an intended change, run the full set and copy the JSON over the baseline,
noting the commit and date here.

## Baseline

Recorded 2026-09-12 at journal step B2 on the development machine: AMD Ryzen 9 7950X3D, 63 GB,
Windows 11, JMH 1.37 forked on JDK 21.0.7, one fork, the class defaults for iterations (three
warmup and five measured of one second; the sailing set two and three of two seconds). Scores
are averages per operation; the generate matrix is in ms/op. The full JSON, with error bars, is
`docs/benchmarks/baseline.json`.

**Reading it.** A cold click on a single-target query costs 100 to 360 ms in the common cases and
600 to 850 for the boat-only island and the wilderness escape; the field flood is most of a cold
generation (270 ms for a full flood, 9 to 27 ms bounded to the cost band). Regenerating toward
the same target costs 16 to 78 ms on the common queries. "Nearest bank" is over a second cold
and three quarters of a second warm: a map-wide target set gets no compact field, so every
search runs uninformed under the walk cap, and the round trip adds another 300 ms of return
searches. A sealed target costs about 700 ms cold or warm: its three escape routes are
uninformed floods the field cache cannot help. The guided search is 50 to 75 us against 45 to
130 ms uninformed; the wilderness escape is the one case the heuristic does not help (3.0 ms
against 2.5), since teleports are blocked where the search starts. The owned and everything
modes measure alike because the bench client carries nothing; a fixture with an inventory would
separate them.

### Generate (ms/op)

| Scenario | owned cold | owned warm | everything cold | everything warm |
|---|---:|---:|---:|---:|
| lumbridge-barrows | 355 | 70.8 | 354 | 74.0 |
| ge-shilo | 313 | 16.3 | 318 | 16.6 |
| capture | 98.4 | 24.4 | 102 | 26.1 |
| island | 602 | 301 | 625 | 308 |
| deep-wild | 355 | 73.8 | 362 | 77.9 |
| wilderness-escape | 816 | 697 | 853 | 711 |
| nearest-bank | 1045 | 757 | 1048 | 774 |
| bank-and-back | 1386 | 1057 | 1323 | 1060 |
| water-pin | 412 | 104 | 406 | 110 |
| sealed | 708 | 720 | 710 | 707 |

### The rest

| SailingSeaBenchmark.seaTrackLeg | Params | Score | Unit |
|---|---|---:|---|
| |  | 52.4 | ms/op |

| SailingSeaBenchmark.wetFloodCoastal | Params | Score | Unit |
|---|---|---:|---|
| |  | 21.0 | ms/op |

| SailingSeaBenchmark.wetFloodMidOcean | Params | Score | Unit |
|---|---|---:|---|
| |  | 41.0 | ms/op |

| AvailabilityBenchmark.rebuildWithExclusion | Params | Score | Unit |
|---|---|---:|---|
| |  | 230 | us/op |

| AvailabilityBenchmark.refresh | Params | Score | Unit |
|---|---|---:|---|
| |  | 3659 | us/op |

| DistanceFieldBenchmark.build | Params | Score | Unit |
|---|---|---:|---|
| | costMultiple=0 scenario=single | 270662 | us/op |
| | costMultiple=0 scenario=multi | 274007 | us/op |
| | costMultiple=3 scenario=single | 8840 | us/op |
| | costMultiple=3 scenario=multi | 26689 | us/op |

| PathfinderBenchmark.search | Params | Score | Unit |
|---|---|---:|---|
| | mode=astar scenario=lumbridge-barrows | 59.7 | us/op |
| | mode=astar scenario=ge-shilo | 66.0 | us/op |
| | mode=astar scenario=capture | 52.3 | us/op |
| | mode=astar scenario=island | 73.4 | us/op |
| | mode=astar scenario=deep-wild | 60.3 | us/op |
| | mode=astar scenario=wilderness-escape | 2952 | us/op |
| | mode=uninformed scenario=lumbridge-barrows | 45358 | us/op |
| | mode=uninformed scenario=ge-shilo | 116332 | us/op |
| | mode=uninformed scenario=capture | 954 | us/op |
| | mode=uninformed scenario=island | 128737 | us/op |
| | mode=uninformed scenario=deep-wild | 132219 | us/op |
| | mode=uninformed scenario=wilderness-escape | 2537 | us/op |

| SearchHeuristicBenchmark.buildHeuristic | Params | Score | Unit |
|---|---|---:|---|
| | scenario=lumbridge-barrows | 6.31 | us/op |
| | scenario=ge-shilo | 6.23 | us/op |
| | scenario=capture | 6.85 | us/op |
