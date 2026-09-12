# Plan journal

One entry per step of the plan from the 2026-09-06 codebase review (the "Now", "Next" and
"Later" tiers). Every step is test-first: the entry names the test that was red before the
change, what the change did, what was measured, and the commit that landed it.

Conventions: costs are in cost units (2 per tick, 0.6 s per tick); timings are from the gated
probes on the development machine (`-Dgps.searchBench=true`, `-Dgps.refreshCost=true`) unless
a field capture is named.

## Now tier (2026-09-07)

Executed in one pass, twelve commits `ab37ba8` through `080ecd8`, before this journal existed.
Summary: the seven live defects (parsers now record unknown quest names and malformed
coordinates; the route-affecting key list is derived and tested; the Wyrmscraig mooring is
deduplicated; the empty NPE catch is gone; a failed generation emits its terminal update;
plugin-message overrides reject undeclared keys), the refresh item-snapshot hoist (main-config
refresh 218 ms to 12 ms), the unreachable-target escape menu (three closest-approach routes
instead of nineteen floods, not in "+ Bank" mode), overlay scene culling and per-frame hoists,
panel mutators marshalled onto the client thread, parallel test forks, LF line endings, the
collision workflow schedule disabled, and `doors.tsv` regenerated. Afterwards: the Pandemonium
charter rows (`f986391`) and the Cabin Fever / Ring of charos fare discount (`619d6bd`).

## Next tier

### Step N1: distance field for multi-target queries (2026-09-07)

**Red first:** `MultiTargetFieldTest` generates a "nearest bank" page (the 296-tile bank set as
the target set) from Lumbridge with every teleport, and from the Feldip Hills walking only, and
asserts that at least 90% of the generation's searches ran with a heuristic and none flooded the
map. Before the change all seventeen Lumbridge searches were blind (`astar=false`).

**Change:** `DistanceField.buildIfCompact` no longer refuses target sets wider than 256 tiles;
`SearchHeuristic.MAX_TARGET_SPAN` is gone. The old rationale ("such searches are already cheap,
and h ~ 0 everywhere") only holds when the nearest target is close; when it is far, each blind
search floods its whole cost ball. The multi-source flood costs the same as a single-target one
and bounds itself the same way.

**Measured (test output):** Lumbridge nearest bank, all teleports: 17 searches, all guided,
154 nodes in total (was ~230k blind). Feldip Hills nearest bank, walk only: 6 searches, all
guided, worst 9,429 nodes, 27k total.

**Not changed:** the single-target unreachable case keeps the escape menu from the Now tier;
"+ Bank" mode still gets a field, but the field's reverse flood ignores banked items (that gap
is on the harvest/compute step's list).

### Step N2: the search hot loop stops allocating (2026-09-07)

**Red first:** `SearchAllocationTest` measures bytes allocated on the search thread per settled
node (HotSpot's thread allocation counter) for a 660k-node blind walk and for a full flood with
120 unreachable targets. Baseline: 131 bytes per node, 87 MB for one walk search.

**Change:** `NodeGraph` is paged (16K nodes per page, no copy on growth; the old flat arrays
copied ~36 MB a dozen times per big search and then released it all); the target check is a
binary search over a sorted `int[]` instead of `Set<Integer>.contains`; door masks come from a
primitive-keyed index (`ClosedDoors.edgeMaskIndex`); bank tiles from a primitive-keyed index;
the cutoff clock is read once per 1,024 nodes; the closest-tile post-pass hoists its unpacks out
of the target loop; tentative-cost pruning now runs in A* mode too (a tile-only heuristic keeps
the ordering exact, and the graph stops holding 3-4x the settled count).

**Measured:** 131 to 42 bytes per node (single target) and the same order with 120 targets.
Search benchmark, blind Lumbridge to Ardougne: 169 ms / 334 ns per node before, 143 ms / 265
ns per node after; the unreachable full flood 351 to 299 ms. A* equivalence tests unchanged.

**Test-design lesson recorded:** an "unreachable" multi-target set must be genuinely unmapped;
one reachable bank ends the search at Lumbridge castle's after ~1,800 nodes, and plane-3 copies
of the banks still land on a palace roof.

**Not changed:** `IntDeque` / `IntMinHeap` still double by copy (a few bytes per node); the
visited/tentative scratch is still allocated per search. Both are small next to the graph.

### Step N3: the per-search availability rebuild stops re-grouping the world (2026-09-07)

**Red first:** `RebuildAllocationTest` builds an "all teleports" planning copy, excludes eight
catalog methods (four origin-less teleports, four origin-bound transports), and measures bytes
allocated by one `rebuildAvailabilityWithExclusions` with and without exclusions. It also checks
the semantics: excluded transports are gone from their origin, and the base availability is
intact after a rebuild with nothing excluded. Baseline: 4,017,408 bytes per rebuild with
exclusions and 3,040,336 with none, at roughly nineteen rebuilds per generation (every chain
iteration, seed, tail and walk search). `TransportAvailabilityFilteredTest` pins the view
semantics (touched origins get a new array, untouched ones share the base array, extras are
appended, the method identity is cached and follows a remapped destination).

**Change:** the refresh captures the two base `TransportAvailability` objects (no exclusions, no
extras) instead of two base transport lists. A rebuild with nothing to remove or add shares those
objects as-is. Otherwise it takes a copy-on-write `filtered` view: `PrimitiveIntHashMap` gained a
clone constructor and an allocation-free `forEach`, only origins holding an excluded transport
get a new array, a map is cloned only once an entry changes, and extras are appended per origin.
`Transport.method()` caches the catalog identity (the old rebuild built a `TeleportMethod` per
usable transport per search just to test set membership); `setDestination` is now explicit and
resets the cache, since the POH remap is the only post-parse mutation of an identity input. All
per-row `TeleportMethod.fromTransport` call sites use the cache.

**Measured (test output):** with eight exclusions 4,017,408 to 535,472 bytes (the four map
clones at 16K capacity); with none 3,040,336 to 0 bytes. Per generation that is roughly 10 MB
instead of 76 MB of rebuild garbage.

**Not changed:** the seed searches still run against a fixed cost ceiling snapshot rather than
one that tightens as cheaper routes are accepted, and the distance field is still rebuilt for a
generation with the same targets as the last. Both are the next step.

### Step N4: the distance field survives across generations (2026-09-07)

**Red first:** `FieldReuseTest` runs seven generations through one service and asserts, via a
new package-private `lastFieldReused()`, that a generation whose inputs match the previous one
reuses its field (same target from another start; same target and mode again) and that any
changed input rebuilds it (a different target; a mode that admits a different usable set; a
skill level drop in an owned mode). The accessor did not exist, so the test was red at compile
time; with a first implementation the "changed usable set" case failed because the test tried
to flip the fairy ring toggle in the all-everything mode, which bypasses every gate but the
structural ones. The test now changes the mode and the skill level instead.

**Change:** the refresh computes a content fingerprint of the usable transports (a commutative
mix of row index and resolved destination per admitted row and bank state), exposed as
`getUsableFingerprint()`. The generator keys its last field on that fingerprint, the user
exclusions, a fingerprint of the synthesized sea legs, the filtered target set and the cost
multiple (a complete field, horizon at MAX, serves any multiple), and reuses the field when the
key matches. The field is immutable once built and only the generation thread touches the cache.

**Correctness fix found on the way:** a resumed generation ("+ more routes") built its field
over the chain's grown exclusion set from the previous generation. A reverse flood over fewer
transports can only overestimate, so that field was not a valid lower bound for the seed and
tail searches, which run with the user exclusions alone. The field is now always built over the
user exclusions, and the chain's first iteration rebuilds its own availability when the two
sets differ.

**Measured (test output):** field builds of 33 to 65 ms in the all-everything mode and 205 to
256 ms in the owned mode (a flood with no cheap teleport floor runs wider); a reused field
costs 0 ms. In the field, a player walking toward a pin regenerates every few tiles with the
same key, so this removes the largest fixed cost of those regenerations.

**Not changed:** the round-trip return field is still built per generation; the seed cost
ceiling is still a snapshot (next).

### Step N5: a tightening seed cost ceiling, dropped on data (2026-09-07)

**Probe first:** `SeedSearchProfileProbeTest` (gated, `-Dgps.seedProfile=true`) prints every
search record of five typical generations. In the all-everything mode the six seed searches of
a generation settle 290 to 476 nodes together, 0 to 1 ms each, already guided by the field and
already capped; a ceiling that tightens as cheaper routes are accepted would save nothing
measurable, so the step is dropped and the walk search keeps its dynamic ceiling alone.

**Recorded for the Later tier (the same probe):** an unreachable exact target (the Broken Raft
deck) still costs two blind floods of 1.4M nodes, ~500 ms each, to produce the escape menu's
closest-approach routes: the field is complete and never reaches the start, so no heuristic
applies and each closest-approach search must exhaust the start's component. And in the owned
mode with nothing in the inventory, Lumbridge to Ardougne runs a 1.4 s generation whose later
chain iterations settle 430k to 460k nodes each (routes at cost 640 to 767 against a best of
261): the field's guidance is exact for the base availability and erodes as the chain excludes
the cheap transports. Neither has a cheap fix; both are noted with numbers.

### Step N6: the planning refresh stops re-reading the config per row (2026-09-07)

**Red first:** `PlanningRefreshCostTest` counts the config and client reads one planning
refresh makes (Mockito invocation counts, deterministic) and asserts budgets of 120 and 1,200.
Baseline: 7,588 config reads and 3,637 client reads per refresh, 143 to 224 ms. The sampling
probe `RefreshProfileProbeTest` (gated, `-Dgps.refreshCost=true`, a stack-trace histogram over
forty refreshes) attributed 63% of the refresh to `TransportTypeConfig.isEnabledInConfig`,
which asked the live config interface for the toggle on every one of the ~14,000 rows (a
ConfigManager lookup each in the client), 25% to a direct fairy ring varbit read in the
classification, and 7% to the detail strings resolving item definitions per requirement.

**Change:** the type config snapshots the as-configured toggles once per refresh and answers
`isEnabledInConfig` from the snapshot; the fairy ring varbit goes through the per-pass memo
(`varbitValue`); item names are cached across refreshes (they never change, an empty string
marks an unresolvable id).

**Measured (test output):** config reads 7,588 to 70, client reads 3,637 to 221, planning
refresh 143 to 224 ms down to 19 to 46 ms (the transport loop alone 12 to 17 ms). This is
client-thread time, paid once per generation, so it is the largest main-loop win of the plan so
far. The remaining profile is the row loop itself (the once-per-id varbit and quest reads, the
availability builder, the primitive map puts); the harvest/compute split would move the loop
off the client thread entirely and is still on the list, at a much smaller prize now.

### Step N7: the field follows reverse edges out of blocked landings; the verdict in every mode (2026-09-07)

**Red first, twice.** `BankModeUnreachableVerdictTest` asked the "+ Bank" mode for a sealed
target (the Broken Raft deck) and expected the escape menu's short-circuit: no walk, seed or
tail pass. It was red because the unreachable verdict excluded bank mode on the theory that the
field floods the inventory-only availability. The field in fact floods both bank states in
every index it builds, so the exclusion was stale; removing it turned that test green and
`SameTailPrefixCapTest` red: Ardougne to Moss Giant Island, fare in the bank, lost its
walk-to-bank ship route because the verdict fired on a reachable target. A probe of the field
in that exact setup showed the flood never left the island: the rope swing's island landing
(2704,3209) is a blocked tile, the walking flood only steps onto blocked tiles that host a
transport origin, so the landing was valued by the post-flood patch after the loop had ended
and its reverse edge into the rope's origin (2709,3209) was never followed. That is a general
field bug, not a bank-mode one: for any target behind a transport that lands on a blocked tile
(a jetty, a platform), everything past the landing read as unreached, so the heuristic sent it
to the floor (overestimating, burying routes through it) and the verdict could call a reachable
target unreachable in any mode. `BlockedLandingReverseEdgeTest` pins it: the rope origin and
the Brimhaven dock must be flooded, and the dock's field value must not exceed the forward
walking cost.

**Change:** `DistanceField.expandWalking` floods a transport landing on a blocked tile in the
loop, at step-off cost, when it is adjacent to a settled unblocked tile (cardinal, or diagonal
with both flanking cardinals open, mirroring the forward blocked-tile rule), so its reverse
edges propagate; the post-flood patch keeps origin-free teleport landings and the horizon
edge. The unreachable verdict now applies in every mode.

**Measured (test output):** Moss Giant Island field: rope origin 15, Brimhaven dock 87, equal
to the forward walking cost from the dock (a tight bound where there was none). Bank mode on a
sealed target: one chain flood instead of two (2.1M nodes instead of 4.2M), the escape menu
instead of a page that skipped it. A* equivalence, hybrid page fill, multi-target field and
uncommon path shape tests unchanged.

### Step N8: parallel-search siblings carry everything the chain's config carries (2026-09-07)

**Red first:** `ParallelCopyFidelityTest` compares every declared field of a refreshed planning
config against its `copyForParallelSearch` sibling by content (reflection), with an explicit
per-copy list that names the reason for each exception. It found five omissions: the type
config's runtime state (the sibling rebuilt it from the live config off the client thread,
losing the refresh's adjustments), the boosted skill levels (a fresh zeroed array on the
sibling, read by the extras gate), the usable fingerprint and the two catalog maps.

**Change:** `TransportTypeConfig` gained a copy constructor that carries states as refreshed and
adjusted without touching the live config; the planning copy constructor uses it; the skill
levels, fingerprint and catalog maps are copied. A new field now fails the build until it is
either copied or listed as per-copy with a reason ("consistent over fast").

### Step N9: one ETA for the card, the overlay and the step list (issue #4) (2026-09-07)

**Red first:** `EtaConsistencyTest` builds a route whose path steps carry cumulative costs and
asserts the overlay's remaining-time table is derived from them (24 units at the end, 7 ticks
left at the step costing 10) and that the card's seconds equal the overlay's seconds at the
start; a second test generates Lumbridge to Varrock and asserts, for every route, that each
path step carries a cost, that the route's total cost is the last step's cost, that the card
and the overlay agree at the start, and that the remaining time never increases along the
path. Red at compile time: path steps had no cost and the overlay's table took only steps.

**Why they disagreed:** the card showed the search's total cost in seconds; the overlay summed
its own per-step estimates (walking legs rounded per leg, per-edge distance capped at ten
tiles, no cost modifiers), then the two were shown side by side for the same route.

**Change:** `PathStep` carries the search's cumulative cost (the graph emits it when the path is
unpacked; -1 for hand-built steps). `RouteDirectionsOverlay.buildRemainingTicks` is exact per
index from those costs and falls back to the step estimates only for cost-less paths; mid-ride
interpolation reads the same table instead of the step's own ticks. `RouteDirections` re-derives
each step's duration from the cost delta over its span, so the step list adds up to the same
number. Round-trip return legs are re-based onto the outbound total; the idle-bank-flip rewrite
preserves costs. The card is unchanged.

**Observed while running the suite:** `KeepSailingTest.aboardGenerationAlwaysCarriesAPureSailRoute`
fails intermittently, before and after this step (fails at the previous commit, passes on a
rerun). It is the next step.

### Step N10: the keep-sailing baseline is never evicted; seed acceptance is deterministic (2026-09-07)

**Probe first:** a gated probe ran the flaky generation three times in one JVM, printing every
search record. In every passing run the pure-sail route was the walk search's own result
(Disembark at Port Khazard, cost 458), appended last as the baseline; the nine cheaper routes
were port-then-teleport chains accepted from the seed pass in completion order.

**Why it flaked:** with ten routes already accepted, the baseline append loop evicts the
costliest unprotected route, and that was the baseline itself: it is not walk-only, and the
sole-port-first protection never applies when every route is port-first. Whether the page held
nine or ten routes before the append depended on the order the parallel seeds completed (the
page-fill ceiling ratchets as routes are accepted), which varies with JIT warmth and load.

**Red first:** `KeepSailingBaselineTest` runs the same generation with a limit of nine (more
than nine cheaper routes exist, so the page is full every time) and asserts a reached pure-sail
route survives, three runs in a row, and that the three pages are identical. Red: the first run
lost the sail route.

**Change:** the baseline append loop never evicts the route it just appended; the seed pass
collects every result first and accepts them in cost-then-signature order instead of
completion order (the seeds take milliseconds each, so nothing is lost by waiting for all of
them, and "consistent over fast" is the standing rule). The original test is stable again.

### Deferred: the harvest/compute split of the planning refresh (2026-09-07)

The review scheduled the split (client thread harvests game state; the row loop runs on the
worker) for the ~150-230 ms the planning refresh cost per generation. After N6 the whole refresh
is 13 to 23 ms of client-thread time per generation, of which the row loop is 12 to 17 ms, and
the two correctness items bundled with it (the bank-mode field verdict and the parallel-copy
omissions) are done as N7 and N8. The split would still touch every client read inside the row
loop (quest states, varbits, item definitions, boat state) for a prize of roughly one frame
per generation, so it is deferred until a capture shows the refresh in a stutter.

### Step N11: amenities searchable by name, aliases, and "nearest X" in the search box (2026-09-07)

**Red first:** `AmenitySearchTest` asserts that "Falador Bank" is one entry of the name index
carrying every booth access tile (27), that amenity categories are grouped one entry per site,
that "ge", "lumby", "wildy" and "fally bank" match what they mean without weakening literal
matches, that "nearest altar", "Nearest Altar", a bare "bank" and "nearest bank and back"
resolve to the nearest-of options while "falador bank" does not, and that category labels read
like the panel's. Red at compile time (no tiles, no parser, no labels).

**Why it was missing:** the index listed only places, landmarks, dungeons, minigames and training
spots; banks (1,345 rows), altars, furnaces, anvils, ranges, water sources, spinning and potter's
wheels were reachable only through the eleven fixed "Find nearest" buttons, while the search
field's own hint promised "Falador bank". There were no aliases and no way to type "nearest".

**Change:** `Destinations.Entry` carries a tile set (one tile for a place, every access tile for
a named amenity) and an optional nearest option; `searchable` groups the eight amenity
categories by site name and lists fairy rings and spirit trees by code; `parseNearest` reads a
leading "nearest" or a bare category word; `categoryLabel` names a category as the panel does.
`SearchAliases` holds the vocabulary (GE, Wildy, Lumby, Fally, Cammy, Ardy, Priff, PC, CW, GWD,
CoX, ToB, ToA, SW, MTA, WT, BF, NMZ, KQ, ZMI) and the matcher scores the expanded query too,
taking the better tier. The panel routes a multi-tile entry through the nearest-category flow
(the route ends at the nearest booth), re-resolves history and favourite entries to their tile
sets by name, prepends a "Nearest bank" row for nearest queries (round trip for "and back"),
and labels amenity rows with their category chip.

**Not done from the report's wording:** results still show straight-line tiles rather than an
ETA. An ETA per result needs a search per row on every keystroke (a field build per distinct
target set, 30 to 250 ms each), and a cheaper proxy would be the misleading number the item
warns about; deferred until results can be priced from one shared field.

### Step N12: first-run guidance and honest states (2026-09-07)

**Red first:** `UnreachableCauseTest` generates from Lumbridge in the owned-inventory mode to
Varrock (reached), to the Mos Le'Harmless charter landing (charter ships only, which need coins
the empty inventory lacks) and to the Broken Raft deck (sealed everywhere), then to the raft
deck in the all mode, and asserts the generator reports NONE, MISSING_UNLOCKS, NO_KNOWN_ROUTE and
NO_KNOWN_ROUTE. Red at compile time (no such verdict existed).

**Change:** when every route of a page stops short and the mode is an owned one, the generator
refreshes an all-everything planning copy on the client thread and floods its distance field
from the targets; the target is "reachable with everything" when that field reaches the start or
a teleport landing. The panel now says "Not reachable with what you have: a route exists with
items or unlocks you lack, switch to All to see it" or "No known route to this destination: the
spot may be sealed off, or the map may be missing a connection", instead of one sentence for
both. The empty state lists the four ways to set a destination (search a place or amenity, a
Nearest button, right-click on the world map, shift right-click a tile). The support
affordances (report an issue, GitHub, Discord) were moved into the burger and then put back in
the header row the same day at the owner's request: they stay visible above the search box.
The best route's card no longer promises "click to hide" (hiding the fallback shows the fallback
again); it says "Showing on map (the best route)", other selected cards say "click to hide".
The bank-mode tooltips name the button ("+ Bank") instead of a mode label that appears nowhere,
"more alternative routes" became "more routes", and two em dashes left user-visible text.

**Measured:** the cause probe runs only for an unreached page in an owned mode: one planning
refresh (13 to 23 ms on the client thread) and one full flood; the test's three owned-mode
generations plus one all-mode generation complete in 2.9 s together.

**Not done:** the menu entries still read "Set GPS Target" and "Clear Path" (a rename changes
what players have learned to click; left for a deliberate decision), and "Travel options" still
parents "Travel methods".

### Step N13: the TSV lint gate is green and actually runs (2026-09-07)

**Red first:** `scripts/check_tsv.py` against `src/main/resources`: 23 of 40 files failed. The
checker knew one file family (a '#'-prefixed transport header, every row with exactly the
header's column count) and none of the others: the `Meta` and `Note` columns, the plain
headers of `destinations*.tsv`, `doors.tsv` and the generated sailing files, the free comment
blocks before those headers, the two headerless box lists, and the fact that every loader
splits without a limit so a row may omit its trailing empty cells (6,482 such rows in
`transports.tsv` alone were "missing tabs"). And none of it mattered: the CSV lint, test and
checkstyle workflows all trigger on a branch named `master` while the repository's default
branch is `main`, so no gate has run on a push here.

**Change:** the checker finds the header per family (comment lines without tabs are skipped; a
'#'-prefixed or plain tab-separated line is the header; a one-column "# Destination" counts;
`destination-exclusions.tsv` and `destination-remaps.tsv` are declared headerless with fixed
column counts), knows every column the loaders read (with integer and plane validators for the
coordinate tables), accepts omitted trailing cells but never extra ones, and skips '#' rows as
the loaders do. The three workflows now trigger on `main`.

**Measured:** 40 of 40 files pass; the per-cell validators (coordinates, skills, items, var
requirements, durations, wilderness levels, consumable flags) run on every transport row for
the first time in weeks and found nothing to report.

### Step N14: the unreachable-pin ratchet became a named list (2026-09-07)

**Red first:** `DestinationsReachableTest.importedDestinationsDoNotRegress` now loads
`src/test/resources/expected-unreachable.tsv` and fails when the file is missing; that was the
red run. A gated run (`-Dgps.writeExpectedUnreachable=true`) wrote the list from the current
audit: 569 pins, the exact number the ratchet held, one row per pin (category, name, x, y,
plane), sorted. The plain run is green.

**Why a list:** a number let any pin trade places with any other (a new data regression hidden
by an unrelated fix), and never said which pins were the backlog. The list fails by name in
both directions: a pin not on it that is unreachable is a regression (or a world update, in
which case the list is regenerated deliberately), and a listed pin that has become reachable
must be removed, so the backlog can only shrink honestly.

### Step N15: every transport endpoint must be standable (2026-09-07)

**Red first:** `TransportEndpointLintTest` walks every loaded transport row (sea legs, sailable
tiles and the instance template band excluded) and requires each origin to be unblocked or
blocked with an unblocked cardinal neighbour (the forward rule for platform origins), and each
destination to be unblocked or blocked with a step-off neighbour (the rule the distance field
mirrors). First run: 218 of 26,961 endpoints fail. The bulk is 142 plain transport rows (Kourend
staircases on plane 1, cave mouths, house portals such as Pollnivneach at 3339,3001 where every
neighbour is blocked), 26 league-season shortcut landings, 11 agility shortcut ends, a few boat,
canoe and ship tiles. Each is a row the search can never use.

**Change:** the offenders live in `src/test/resources/expected-unstandable-endpoints.tsv` (one
audit line each, 196 distinct), the same two-way contract as N14: a new one fails by name, a
fixed one must be removed, `-Dgps.writeExpectedEndpoints=true` regenerates after a cache
refresh. `PathfinderConfig.getAllTransports()` exposes the loaded rows for the lint.

**Not done:** the rows themselves. Each entry needs a field check (is the tile really
unstandable, or is the collision map wrong there); the list is the worklist.

**Next tier status:** N1 to N15 close every item of the review's Next tier except two
deliberate deferrals (the harvest/compute split, and ETA-priced search results), both recorded
above with their numbers. The Later tier (route acceptance extraction, services out of the
plugin class, one config source of truth, type switches to data, the leagues decision, product
features) is untouched and is a choice to make, not a list to run.

## Later tier

### Step L1: one copy of the route acceptance rule (2026-09-07)

**Test first:** `RouteAcceptanceTest` pins the rule as pure functions: the band applies only
once the page holds four routes and prices off max(best, 35) times the multiple; the page-fill
ceiling ratchets with the costliest accepted route (168 for best 10, costliest 150, multiple
3); the chain stops on a full page past the band while the evicting passes do not; closeness
(an unreached result with the best route reaching is a cap truncation, an unreachable target
accepts routes within ten tiles of the best approach, nothing is too far before the first
route); eviction picks the costliest evictable route strictly costlier than the candidate and
never the sole port-first route; the port-first helpers. The class and its test were written
together (a characterization of code that existed, not a behavior change), and the generator's
own tests (hybrid page fill, same-tail prefix cap, keep-sailing baseline, unreachable
short-circuit, bank mode, round trips) stayed green as the behavioral guard.

**Change:** `RouteAcceptance` holds the band, the page-fill ceiling, the best-cost cap, the
closeness rule, the eviction index and the port-first, nested-route and redundant-hop
predicates. The exclusion chain, the seed pass, the tail-diversity pass, the seed search's
pre-filter and the two baseline appends call it; the three hand-written eviction loops became
one call with a predicate each. The service went from 2,449 to 2,220 lines and no longer
carries the four tuning constants.

**Measured:** full suite 674 tests green. No timing change expected or measured: the rule is
pure arithmetic over a page of at most ten routes.

**Next:** the phase split (a generation context object instead of the ten-to-fifteen
parameter signatures, then the chain, seed, tail and round-trip passes as separate units).

### Step L2: one generation context instead of parameter lists (2026-09-07)

**Guard:** a pure refactor with no behavior change, so the test is the existing suite (674
tests, every generation-level scenario included), run before and after.

**Change:** `Generation` holds a generation's query (start, targets, exclusions, mode, limit,
multiple, round trip, listener, timer), its page under construction (routes, seen signatures,
catalog, unavailable methods, seed candidates) and its search inputs (the distance field, the
closeness baseline, the keep-sailing baseline). The walk, seed, tail-diversity and round-trip
passes take it (and their own one or two arguments) instead of 8 to 18 parameters each; the
seed pass's own worker submission dropped from 13 arguments to 7. Each pass unpacks the context
at its top so its logic reads exactly as before. `stale()` and `oneWayListener()` replace two
idioms that were repeated by hand (the round-trip suppression of streaming, the generation
guard).

**Next:** split the 717-line `computeRoutes` into the prepare, chain, fill, baseline and finish
phases over the same context.

### Step L3: the generation as phases (2026-09-07)

**Guard:** the existing suite again (674 tests), a pure restructuring with no behavior change.

**Change:** `computeRoutes` is now eleven lines: `prepare` (resume or refresh, sea legs; null
when the generation ended there), `buildField` (the field, its reuse, the unreachable verdict),
`startWalkSearch`, `runChain` (false when superseded mid-chain), `fillPage` (seeds and tail
diversity, the "more routes likely" flag), `appendBaselines` (walk and keep-sailing baselines,
ordering, the cut at walking) and `finish` (round trips, timing summary, resume state, the
unreachable cause, the terminal update). Each phase is the former text sliced at its comment
boundary with an unpack prologue, and the state that crosses phases (the chain's exclusion
set, the tail counts, the closeness baseline, the field and its verdict, the walk search and
its ceiling, the two chain outcome flags) lives on the context. The largest method in the
service is now the seed pass at 223 lines; the chain is 216.

**Not done:** the passes still unpack the context into locals rather than reading it directly
(a readability follow-up with no behavior at stake), and the seed pass itself could split into
attempt selection, the parallel run and acceptance.

### Step L4, attempted and reverted: the eighteen hidden type toggles (2026-09-07)

**Intent:** remove the eighteen transport-type toggles that exist as hidden config items with no
panel control (the owner asked what the startup line "clearing stranded hidden toggle ..." is
for), and with them the clearing routine, keeping the plugin-message override under the same
keys.

**Red first:** a test asserting no unsurfaced type toggle is a config item, that an override
still gates a type, that the keys stay declared, and that the clearing routine is gone.

**What stopped it, with numbers:** RuneLite's config proxy (client 1.12.35,
`ConfigInvocationHandler.invoke`) returns null for any interface method without `@ConfigItem`
before it considers default methods, so the toggles cannot become plain interface defaults (a
primitive getter would throw). Deleting the methods instead compiles, but the suite encodes
"type disabled unless this test enables it" through Mockito's default `false` for unstubbed
getters: 13 tests in 5 classes changed meaning (routes that used to avoid gliders, spells and
levers now take them), behind 150 stub lines in 29 files. Making those tests say what they mean
(explicit overrides per class) is a suite-wide rewrite for the prize of eighteen dead items and
one log line.

**Decision:** reverted in full. The startup clearing routine stays as the correct guard (it fires
once per stale key and is silent afterwards). The right moment for this cleanup is the config
model step proper: a surfaced flag on `TransportType`, and a shared test fixture that states
each test's enabled types explicitly, so the mock default stops carrying meaning.

### Step L5: the type switches became data (2026-09-07)

**Red first:** `TransportTypePresentationTest` requires a presentation row for every
`TransportType` constant and pins the values the switches produced (the catalog category, the
vehicle word of a route label, the owning Travel-options name, the type-level gate quest) plus
the hull tiers (raft 0, skiff 1, sloop 2, an unknown tier shows nothing). Written together with
the table and green on the first run, which proved the row list complete.

**Change:** `TransportTypePresentation` is one table, one row per type; `TeleportMethod`'s
vehicle-phrase and category switches, `PathfinderConfig`'s gate-quest and travel-option switches
and the three hand-written type-gate checks in the classification delegate to it. `BoatHull`
replaces the hull-name switch in the plugin and the icon switch in the panel (the glyph mapping
sits with the icons). Six switches became two data holders; a type added to the enum now fails
the table test instead of falling through five defaults.

**Deliberately not done:** `ItemVariations` stays an enum rather than a TSV. Its rows are
`ItemID` constants from RuneLite's game values, which fail loudly at compile time when RuneLite
renames them; a TSV of raw ids would lose that and gain nothing the code needs.

**Later-tier status:** L1 to L3 (the acceptance rule, the generation context, the phases) and
L5 are done; L4 (the hidden toggles) is recorded above as reverted with its reasons. What
remains is the large structural item (the services out of the 4,800-line plugin class), the
config model proper, the leagues decision (the owner's call), and the product features.

## Services out of the plugin class (2026-09-09)

The owner chose the structural item next, test first for each service where a unit test proves
the seam. The order runs from the most self-contained seam outward: the journey timer, the
boat banner, the plugin-message codec, the route session, then the tick handlers.

### Step L6: JourneyTracker (2026-09-09)

**Red first:** `JourneyTrackerTest` pins the timer's contract: standing still after arming
starts nothing and reports zero elapsed, the first tick that moved starts the clock (not the
tick that merely recorded the position), an animation starts it without moving (a teleport
cast is the first action), and re-arming forgets the old journey without a phantom move on the
next tick. The class did not exist; the test was red at compile time.

**Change:** the two plugin fields (start, last location), the tick block, the elapsed
computation, the getter and the arm call became `JourneyTracker` (arm, tick with the location,
the acting flag and a clock parameter, start, elapsed). The plugin delegates.

### Step L7: BoatBannerService (2026-09-09)

**Red first:** `BoatBannerServiceTest` with a mocked client and config manager: an owned boat
becomes a {name, port, hull} row from the varbits and the game's name-part tables and persists
as "name|port|hull"; a boat with a set descriptor but a lagging owned flag still counts (Port
Sarim is port 0); a dirty mark rebuilds once on the next tick and a quiet tick rebuilds
nothing; restore reads the snapshot once (rows without a name dropped, older rows without a
hull tolerated) and is not "live"; logout forgets everything; not logged in means no rebuild
and no write; the tracked varbits and the wire format. One expectation of mine was wrong on
the first run (hull varbit 0 is the raft, as the plugin always mapped it), fixed in the test.

**Change:** the banner rows, the live and dirty flags, the five-boat varbit table, the rebuild,
the name decode, the persistence and its restore, the logout reset and the per-tick check left
the plugin for `BoatBannerService`, constructed at startup before the panel with the panel's
refresh as its change callback. The plugin keeps two delegating getters and a one-line varbit
handler. Plugin class: 4,975 to 4,843 lines.

### Step L8: PluginMessageCodec (2026-09-09)

**Red first:** `PluginMessageCodecTest`: both namespaces are ours (the pre-fork one keeps older
integrations working); a start and a target arrive as packed integers or world points and a
missing start means the player; a set of targets mixes both shapes and an undefined member
drops the whole request rather than half of it; an override-only message is not a path request
and a non-map override is empty; an unknown target type means keep the current destination;
the published transports keep the parallel-list shape older consumers read. The existing
"PluginMessageTest" is a manual launcher, so this is the first unit coverage of the wire format.

**Change:** parsing and encoding moved to `PluginMessageCodec` (namespaces, actions, keys, a
`PathRequest` value, `parsePath`, `configOverrideOf`, `encodeTransports`); the plugin's handler
applies the override, resolves the player start, expands the targets and sets the destination
as before, and the publisher encodes through the codec. Seven message constants left the plugin.
Plugin class: 4,843 to 4,762 lines.

**Open question, not changed:** an override-only message still applies its override and
returns (the review called that a defect: "validate first, apply after"). Kept as-is here
because this step is a refactor; the codec now makes the two steps separable when that call
is made.

### Step L9: RouteSession (2026-09-09)

**Red first:** `RouteSessionTest` pins the session's contract with synthetic routes: a fresh
destination shows nothing until its routes settle (a streaming front-runner is never drawn);
selection toggles and falls back to the best; a same-destination regeneration keeps the picked
route on screen while the fresh page computes and re-matches the pick afterwards (even at
another cost); a never-started pick yields to a route more than twice cheaper but is kept once
under way; the re-match matches what is LEFT of the plan (methods whose edges are behind the
player are consumed); a new destination clears the display and a page generated for another
destination is never shown; "more" widens the band and the count up to the cap and remembers
the budget the generation ran with; resort and the auto-compute decision.

**Change:** the eleven alternative-routes fields (the page, the pick, the committed display
route, the in-flight flag, the last start, targets and limit, the "more" flag, the limit and
the cost multiple) and the decisions over them (begin, stream, settle with the re-match rule,
displayed, select, resort, widen, the auto-compute decision) are `RouteSession`, owned by the
client thread and read by the overlays and the panel. The plugin keeps the side effects:
panel refreshes, the journey timer, plugin messages, persistence, thread hops. Three existing
tests that reached the old plugin fields by reflection now resolve them through the session.
Plugin class: 4,762 to 4,601 lines.

### Step L10: OffRouteTracker, and the tick as named steps (2026-09-09)

**Red first:** `OffRouteTrackerTest`: recalculation disabled means no warning and no path scan
(the scan is a supplier, consulted only when something can come of it); the three bands (on
route, warning, recalculate); recalculation needs a move and the setting, and may cancel
instead; the helm stretches the bands (recalc x2, warn x3); a transport jump arms a grace
window that holds for its twenty ticks, clears at once back near the path, and lets the next
far move recalculate; no same-plane tile means on route. The class did not exist.

**Change:** the off-route state (last location, grace ticks, distance, warning) and the band
decision became `OffRouteTracker`, with a verdict the plugin acts on (cancel the target, or
recalculate from here when no generation is in flight). The two overlay getters delegate. The
164-line tick handler is now the sequence it always was, as named steps in the original order:
refresh the catalog if due, cache the player location, the boat banner, sea-obstacle learning
every ten ticks, pending tasks, auto-compute, the house and balloon varbits, the house scan,
then for a set destination the journey and arrival, then the off-route bands. Plugin class:
4,601 to 4,587 lines (the steps' javadocs replaced inline comments).

### Step L11: PohDetectionService (2026-09-09)

**Red first:** `PohDetectionServiceTest`: outside a house nothing is scanned; inside, the
first find raises only the declarations that were off or lower (the fairy ring on, the
jewellery tier from Basic to Fancy), persists the encoded result once, notifies the panel once,
and stops rescanning for the visit; a bare house is scanned six times then left alone, with
one persisted empty result; spawned furniture alone counts as being inside and joins the scan;
leaving building mode re-arms the scan, a fresh visit rescans, and an unchanged result does not
persist again; smart detect off means no scan; restore reads the snapshot only when nothing was
scanned this session, and reset forgets it. The class did not exist. Two test corrections along
the way: the jewellery tier prints as its display name (Fancy, not FANCY), and the fake
declarations had to take effect on a raise the way the real config does, otherwise the second
scan raised the fairy ring again.

**Change:** the eight detection fields, the tick cadence, the scan, the raise-only
declarations, the persistence, the restore and the two resets became `PohDetectionService`.
It reads the world through two small interfaces the plugin implements against the client: a
Scene (is it a house, is it an instance, the chunk description for the debug log, the object
ids of the tile walk) and the Declarations (the four config reads, and a raise that writes
through the panel path). The plugin keeps the client-bound parts only: the template-region
test, the tile walk, the spawn event handler (one line), and the two panel getters. The
config key moved with the service. Plugin class: 4,587 to 4,514 lines.

**Suite:** 714 tests, all green (707 plus the seven new ones).

## Modules out of the plugin class, by concern (2026-09-09)

After L11 the plugin class still held 4,514 lines. The owner's criterion is readability and
maintainability: dedicated, specialized modules beat a huge file that mixes concerns, whether or
not a seam yields a new test. So the remaining concerns move out one by one, largest and
cleanest seams first, with a test where there is logic to pin.

### Step L12: BuildInfo, IssueReport, DebugSnapshot (2026-09-09)

**Red first:** `DiagnosticsFormatTest` pins the pure formatting the two reports share: a packed
point as "x, y, plane" or "(none)", a route's methods as "A + B" or "walk", and a packed point
as a {packed, x, y, plane} JSON object (key order is what the dashboard reads) or null.
`IssueReportTest` moved its two assertions (bare new-issue link, manifest version) to
`BuildInfo`. Red at compile time.

**Change:** the build identity (manifest version, stamped commit, the bare GitHub link) became
`BuildInfo`, one property reader instead of two copies; public, because the dev audit panel in
`gps.dev` stamps itself with it. The issue text became `IssueReport` (the body, the item-name
listing, the point and method text). The debug capture became `DebugSnapshot`, its 180-line
lambda split into build, varbit snapshot, route JSON, generation timing and write. Both read the
plugin through nine package-private accessors (session, catalog, unavailable map, house
detection, spirit-tree flag, the generation service, the directions overlay, Gson). The plugin
keeps two entry points: `reportIssue` (client thread body, then the panel and the link on the
EDT) and `captureDebugSnapshot` (one line). Three imports went with the code. Plugin class:
4,514 to 4,124 lines.

**Suite:** 717 tests, all green.

### Step L13: WorldMapProjection and MinimapClip (2026-09-09)

**Red first:** `WorldMapProjectionTest` pins the projection with a mocked map (zoom 4, centre
3200,3200, widget at 100,50 sized 800x600): the tile 3210,3210 lands at pixel 542,308 and that
pixel maps back to the tile; without the map widget both directions return the sentinel.
`MinimapClipTest` pins the mask-to-polygon walk: a 4x4 mask with a 2x2 opaque block yields four
points running down the left edge and back up the right, offset to the widget position. The
classes did not exist when the tests were written.

**Change:** the three world-map pixel methods became `WorldMapProjection` (`toGraphicsX`,
`toGraphicsY`, `worldPointAt`, sentinel `NONE`); the minimap draw widget, the two clip shapes,
the cached sprites and rectangle and the polygon walk became `MinimapClip` (`area()`, static
`polygonOf(image, offsetX, offsetY)` so the outline takes its offset as an argument instead of
reading a field). Both are constructed in `startUp` and reached through two package-private
accessors; the map and tooltip overlays call the projection, the minimap overlay clips to
`minimapClip().area()` once instead of computing it twice. Five imports went with the code.
Plugin class: 4,124 to 3,939 lines.

**Suite:** 720 tests, all green.

### Step L14: RoutePreferences (2026-09-09)

**Red first:** `RoutePreferencesTest` takes over the ranking cases that `MethodPriorityTest` and
`KeepSailingTest` used to drive through a bare plugin by reflection (a preferred method outranks
a raw faster route, an avoided one sinks, the walk preference gives the pure-walk route slack,
the bank bias shifts via-bank routes, adjustments never promote an unreached route, at the helm
pure sail leads, excluded methods read back as EXCLUDED, exclusion masks but does not erase the
tier) and adds the persistence round trip: tiers, walk and bank seconds save through the config
manager and reload; a tier set back to NORMAL does not persist. The class did not exist.

**Change:** the tier map, the walk and bank seconds, the adjustment arithmetic, the effective
order comparator and the save/load became `RoutePreferences`. It reads the plugin's live
exclusion set for the EXCLUDED mask and takes the keep-sailing verdict as a supplier; the config
manager and Gson arrive as suppliers too, because the plugin's injected services are not there
at field initialisation and two tests drive the update stream on a bare plugin. The plugin
keeps the public API the panel uses, one line each, plus the exclusion dance around a tier
change (EXCLUDED delegates to the exclusion set, any other tier un-excludes first) and the
re-sort that follows. `MethodPriorityTest` now holds only the enum's own arithmetic and labels.
Plugin class: 3,939 to 3,832 lines.

**Suite:** 721 tests, all green.

### Step L15: ChoiceStore (2026-09-09)

**Red first:** `ChoiceStoreTest`: the routes mode reloads by name, the three legacy 3-mode
names map onto the Owned/All split, anything else means "keep the default"; the exclusion set
reloads from JSON dropping typeless entries and the seasonal methods a prior version seeded
there, rewriting the cleaned set once and only when something was dropped; the search history
and favourites round-trip through their codec. The class did not exist. One test fix:
destination entries carry no equals, so the round trip compares the three codec fields.

**Change:** the four persisted choices (exclusions with their seasonal migration, routes mode
with its legacy names, search history, favourites and its limit) became `ChoiceStore`, with the
mode decoder a pure static. The plugin keeps the live copies and calls the store on change: the
five exclusion save sites, the mode change, the history and favourite writes, and the startup
loads (the saved mode applies only when one exists). Plugin class: 3,832 to 3,737 lines.

**Suite:** 725 tests, all green.

### Step L16: BankSnapshotService (2026-09-09)

**Red first:** `BankSnapshotServiceTest`: the first sight of the live bank is reported once (a
deposit is not a first sight); a save is staged with the profile key of the moment and written
once, an empty bank dropping the stored snapshot instead; nothing is staged with the setting
off; a restore fills in only before the live bank and is superseded by it; a restore is skipped
with the setting off; logout persists then forgets (the pathfinder's bank cleared); turning the
setting off drops the stored snapshot and, when this session's knowledge came from it, the
knowledge too; turning it on saves a live bank at once. The class did not exist.
`BankSnapshotPersistenceTest` (the id:quantity codec) re-targeted its calls.

**Change:** the four bank fields (known, restored, save-dirty, profile key), the container
event's bank branch, persist, restore, the logout forget, the two rememberBank branches and
the codec became `BankSnapshotService`, constructed in `startUp` right after the pathfinder
config it writes to. The plugin's handlers are now one call each: adopt the live bank at
startup, `bankOpened` in the container event (its return value drives the first-sight
regeneration), `persist` on bank close and shutdown, `forget` at logout, `rememberNow` and
`forgetStored` on the setting. Plugin class: 3,737 to 3,591 lines.

**Suite:** 733 tests, all green.

### Step L17: SpiritTreeSync and FairyRingHighlighter (2026-09-09)

**Red first:** `SpiritTreeSyncTest` pins the travel-menu parse as a pure function over the row
texts: old-menu rows yield the usable trees and skip the greyed ones, new-menu rows use the
white number colour (and the old pattern reads nothing from them), another menu (first row
not the 39-character Tree Gnome Village row, empty, null) is not parsed. The class did not
exist.

**Change:** the two label patterns, the config key, the parsed-live flag, the widget-loaded
gate, the parse, the restore and the logout reset became `SpiritTreeSync`; the plugin passes
what happens after a sync as a callback (refresh the panel section, regenerate when a
destination is set). The fairy-ring log state and its 97-line scroll became
`FairyRingHighlighter`, the row lookup written once instead of twice and the route's code a
static over the path and an edge-transport function. Both are constructed in `startUp` with the
pathfinder config; the three widget handlers and the post-client-tick are one call each, the
two panel getters and the diagnostics accessor delegate. Two imports went with the code.
Plugin class: 3,591 to 3,377 lines.

**Suite:** 736 tests, all green.

### Step L18: PlayerOwnedHouse (2026-09-09)

**Red first:** `PlayerOwnedHouseTest` pins the exit label as a pure function over the path and
an edge-transport function (mocked transports): a fairy ring, a mounted glory, a plain
jewellery box, the nexus, a spirit tree, the obelisk and a plain transport each get their
label; a destination outside, an exit edge without a transport, an index already past the
exit, and a null path yield nothing. The class did not exist. `PohSceneDetectionTest`
re-targeted its calls.

**Change:** the house bounds, the template regions, the scene test, the exit lookup (the
113-line method split into the edge walk and a label function) and the two adapters the
furniture detection reads the client through became `PlayerOwnedHouse`, public because the
pathfinder package and its tests ask whether a tile is in the house. Eight call sites moved
with it (the pathfinder config, the availability filter, two overlays, the debug snapshot and
three tests); the overlays pass the plugin's edge-transport method to the static exit lookup.
Two imports went with the code. Plugin class: 3,377 to 3,102 lines.

**Suite:** 738 tests, all green.

### Step L19: ArrivalZone, SeaObstacleLearner, and the path distance (2026-09-09)

**Red first:** `ArrivalZoneTest` pins the flood against a mocked collision map: open ground
gives the 3x3 block at one step, a wall to the east closes that tile and both diagonals beside
it, no map or no steps is the end tile alone, and the zone is cached per path end and radius
(the same set instance comes back for the same end, a wider radius recomputes, a negative
radius or an empty path is empty). `OffRouteTrackerTest` gained the distance measure: zero on
a path tile, Chebyshev to the nearest tile otherwise, -1 without a path. The classes did not
exist.

**Change:** the arrival cache, the flood and its corner rule became `ArrivalZone` (the plugin's
`getArrivalTiles` is one line over the displayed path and the finish distance; `hasArrived`
stays, it reads the session and the round-trip state). The ten-tick scene scan became
`SeaObstacleLearner`, its magic numbers named (scan period, edge margin, own-boat radius). The
distance from the displayed path joined `OffRouteTracker` as a static, and only consults the
sea when the route has a sailing leg, so a land route never loads the ocean. Plugin class:
3,102 to 2,898 lines.

**Suite:** 742 tests, all green.

### Step L20: CompanionPlugins and SidebarButton (2026-09-09)

**Red first:** `CompanionPluginsTest` drives the checks with a mocked plugin manager and three
tiny annotated plugin classes: an enabled Shortest Path is a conflict and the change callback
fires once, not again for an unchanged verdict, and again when it is disabled; Quest Helper
enabled with its option off means pathing off, the option on clears it. `SidebarButtonTest`
pins the mount rule against a mocked toolbar: added once on LOGGED_IN, untouched by LOADING and
HOPPING, removed once on the login screens, and removed on shutdown. The classes did not
exist. One test fix: the navigation button is a final class, so the test builds a real one
(its builder needs no client).

**Change:** the two descriptor-name scans became one pass in `CompanionPlugins`, with the two
verdicts and a change callback (the plugin refreshes the panel). The sidebar button's mount
state and the game-state rule became `SidebarButton`, which the focus-search hotkey opens and
shutdown removes. The plugin keeps the two panel getters, the three event handlers (one call
each) and the config-change hook. One import went with the code. Plugin class: 2,898 to 2,795
lines.

**Suite:** 745 tests, all green.

### Step L21: ConfigOverrides, and the external-target expansion (2026-09-09)

**Red first:** `ConfigOverridesTest`: an applied override answers instead of the config value
by type (boolean, int, teleport-item and jewellery-tier names, colour), an unknown key is
rejected, a key the message did not set keeps the config value, a value of the wrong type
falls through, a new message replaces the previous set, clearing restores every config value,
and the route-affecting test names the keys the engine reads. `ExternalTargetsTest`: a
transport origin among another plugin's targets wins alone, otherwise every target stands. The
class and the method did not exist.

**Change:** the override map, its six typed reads (the colour one was an instance method for
no reason), the known-key set and the route-affecting pattern became `ConfigOverrides`,
public because the pathfinder config and the transport type config read it (twenty-one call
sites re-pointed, plus the two reports and the keys test). The message handler applies a
message's overrides in one call. The Quest Helper target expansion (walkable ring, then the
transport origins alone when any) joined `Destinations` beside the pin expansion it builds on.
Three imports went with the code. Plugin class: 2,795 to 2,631 lines.

One tooling lesson: the patch script appended the Destinations method before its own checks
ran, so two failed attempts left it three times over. Scripts now write nothing until every
check passes.

**Suite:** 750 tests, all green.

### Step L22: MapMenu (2026-09-09)

**Red first:** `MapMenuTest` pins the one pure piece, the icon-target-to-destination-key
rule (colour tags stripped, lower case, letters and spaces only, words joined by underscores).
The class did not exist. One expectation dropped: a target with a trailing digit leaves a
trailing underscore, an artifact not worth pinning.

**Change:** the six menu strings, the menu-opened point, the entry-added logic (shift-click
tile, world map, minimap, floating-map controls, "Find closest" on a known icon), the click
dispatch, the selected-tile resolution and the duplicate-safe entry insertion became `MapMenu`,
built in `startUp` over the map projection and the minimap clip. The plugin keeps two one-line
event handlers and gained three package-private actions the menu calls (`pinTarget`,
`clearPinnedTarget`, `findClosest`), which also own the "map pin" attribution the click
handler used to set inline. Eleven imports went with the code. Plugin class: 2,631 to 2,517
lines.

**Suite:** 751 tests, all green.

### Step L23: EdgeTransports, and the route's own method edges (2026-09-09)

**Red first:** `EdgeTransportsTest` pins the edge rule with a mocked pathfinder config and
mocked transports: a local quetzal on the edge suppresses the whistle that shares its
destinations but keeps a tablet; a far jump without the local type keeps both teleports;
walking to the landing within the quetzal radius drops the whistle; an adjacent same-plane
step hints no teleport; and the next-step lookup. `RouteMethodEdgesTest` pins the route's own
"which method arrives at this index". Neither existed.

**Change:** the per-edge transport derivation and the next-step lookup became `EdgeTransports`
(the essay-length javadoc that had drifted onto a different method went with it); the method
arriving at an index became `RouteOption.methodArrivingAt`, so the two overlay queries on the
plugin are one line each. The plugin keeps `transportsForEdge` as a one-line public delegate
(overlays, directions, the message codec and the house exit lookup all take it), and the
tooltip overlay calls the static next-step directly. Three imports went with the code. Plugin
class: 2,517 to 2,403 lines.

**Suite:** 757 tests, all green.

### Step L24: OverlaySettings (2026-09-09)

**Red first:** `OverlaySettingsTest`: the snapshot copies the config (a mocked config), a
plugin-message override wins over it, the font size is display-only and never overridden, and
a snapshot taken earlier does not change under a later override. The class did not exist.

**Change:** the twenty-three display fields the overlays read every frame, and the config
cache that filled them, became `OverlaySettings`: one immutable snapshot per config change,
overrides applied at snapshot time. The overlays read it through `plugin.display()` (thirty
reads re-pointed across the five overlays); the plugin's own four reads (path colours, the
unreachable threshold) do the same. `cacheConfigValues` is two lines. Plugin class: 2,403 to
2,362 lines.

**Suite:** 759 tests, all green.

### Step L25: PanelVarbits (2026-09-09)

**Red first:** `PanelVarbitsTest` pins the two pure mappings: the house varbit to a location
name (none without a house or past the table) and the six balloon unlock varbits to the log
types that have a route (normal logs need the quest at 2, the rest a first flight at 1, in
the log-storage order). The class did not exist.

**Change:** the house-location id, the location table, the balloon unlock array, the per-tick
cache and the two balloon getters became `PanelVarbits` (the varbit ids named, the unlock rule
a static, the stored counts a static over the config). The plugin's tick step and three panel
getters are one line each. Plugin class: 2,362 to 2,331 lines.

**Suite:** 761 tests, all green.

### Step L26: MethodExclusions (2026-09-09)

**Red first:** `MethodExclusionsTest`, over a real `ChoiceStore` on a mocked config manager:
every real change persists once and notifies once, a no-op change (excluding an excluded
method, clearing an empty set) does neither, and the route list is stale after a change until
a generation marks the set as used. The class did not exist.

**Change:** the live exclusion set, the generated-with snapshot and the five mutators (with
their save-and-refresh) became `MethodExclusions`. The plugin keeps the public API as one-line
delegates (single changes still hop to the client thread, the panel's bulk toggles still write
the concurrent set directly), the stale check, the generated mark, and passes the live set by
reference to the ranking preferences and the generation as before. One Java lesson: a field
initializer's lambda cannot name a field declared later in the class, even lazily; `this.`
qualifies it past the rule. One import went with the code. Plugin class: 2,331 to 2,271 lines.

**Suite:** 766 tests, all green.

### Step L27: SearchMemory (2026-09-09)

**Red first:** `SearchMemoryTest`, over a real `ChoiceStore` on a mocked config manager: a
favourite with the same label replaces the old one, removal matches label and position, the
favourite list is capped, a selection goes to the front of the history (deduplicated), and
every change persists. The class did not exist.

**Change:** the history and favourite lists, their three mutators and the startup load became
`SearchMemory`; the plugin keeps five one-line public delegates for the panel. Plugin class:
2,271 to 2,246 lines.

**Suite:** 766 tests, all green.

### Step L28: DirectionsCache and RouteVerdicts (2026-09-10)

**Red first:** `RouteVerdictsTest`: a route that stopped short is judged by its endpoint against
the nearest target and the tolerance (two tiles beside an object destination counts as reached,
thirty tiles is cut off, a wider tolerance forgives it); a reached route is never too far and
always reaches; without targets "too far" is false and "reaches" is true (not enough information
to declare it unreachable); a null route reaches nothing. The class did not exist.

**Change:** the two endpoint judgements and the click-walk gate became `RouteVerdicts`, pure over
the route, the targets, the tolerance and (for the gate) a door-state predicate the plugin
supplies from the client. The per-route directions holder became `DirectionsCache`. The plugin
keeps the public delegates the overlays, the panel and the directions builder call. Plugin
class: 2,246 to 2,160 lines.

**Suite:** 768 tests, all green.

### Step L29: CatalogRefresher (2026-09-10)

**Red first:** `CatalogRefresherTest`: nothing to claim until something changed; only a changed
routing-item fingerprint dirties the catalog (the first one always counts); a dirty catalog waits
for the panel, a lull between generations and a login, keeping its flag until claimed; bursts
coalesce through the five-tick cooldown, the kept flag claimed once it lapses. The class did not
exist.

**Change:** the dirty flag, the fingerprint memory and the cooldown became `CatalogRefresher`;
the item-container handler notes the fingerprint and the tick step claims a refresh. Two tests
that reached the plugin's dirty flag by reflection (`CatalogStutterHotfixTest`,
`RoutingItemDependenciesTest`) now reach the refresher's. Plugin class: 2,160 to 2,126 lines.

**Suite:** 772 tests, all green.

### Step L30: the arrival rule on ArrivalZone (2026-09-10)

**Red first:** three cases added to `ArrivalZoneTest`: arrival is the zone, or a mooring within
the sea distance of a sailable target (a land target gets no sea tolerance); a round trip
completes only past its turnaround (two steps short counts), and is suspended while a round trip
is wanted but only the one-way fallback, or nothing, is displayed; an unreachable one-way target
never completes, while the flag does not stop a round trip past its turnaround. The function did
not exist.

**Change:** `hasArrived` became `ArrivalZone.arrived`, pure over the location, the zone, the
targets, a sailable predicate (the plugin passes the sea map's), the sea distance, the displayed
route, the round-trip wish, the progress and the unreachable verdict. The plugin's method is the
one call. Plugin class: 2,126 to 2,080 lines.

**Suite:** 775 tests, all green.

### Step L31: PluginMessageBridge (2026-09-10)

**Red first:** `PluginMessageBridgeTest`, over a mocked plugin and event bus: a foreign
namespace is ignored; a new target attributes the source, arms the journey and expands the tile
to its walkable ends; an empty target keeps the current destination and the running journey;
without a start the player's position stands in, or the request is dropped when there is none;
config overrides apply before the request, and a clear drops both the overrides and the
destination; the displayed route's transports go out on the gps namespace and the legacy
shortestpath one, only when the setting is on and a route is displayed. The class did not exist.

**Change:** the inbound path/clear flow and the outbound broadcast became `PluginMessageBridge`,
with the namespace rationale (why the legacy channel stays answered) in its javadoc; the plugin
keeps a one-line handler and gained three small package-private hooks (apply and clear the
config overrides, attribute the target source), and the journey arm is package-private. The
bridge is built at field initialisation with a supplied event bus, the pattern the choice store
uses, so tests that drive the route update on a bare plugin never meet a null. Plugin class:
2,080 to 2,012 lines.

**Suite:** 783 tests, all green (the eight new ones include the hotkeys test of L32, written in
the same pass).

### Step L32: PluginHotkeys (2026-09-10)

**Red first:** `PluginHotkeysTest`: each hotkey fires on its own binding only, modifiers
included, and the arrival click is consumed only when it dismissed the panel, so an ordinary
click still reaches the game. The class did not exist. One finding on the way: RuneLite's
`Keybind` matches on the event's EXTENDED key code, which the native layer fills in and a
hand-built KeyEvent leaves at zero, so the test's press mirrors it from the key code.

**Change:** the clear-path and focus-search key listeners and the arrival-dismiss mouse listener
became `PluginHotkeys`, built once with the bindings as suppliers (read on every press, so a
config change applies at once) and registered and unregistered in one call each; the plugin
keeps the two actions (clear the target, open the panel and focus its search box). Plugin
class: 2,012 to 1962 lines.

**Suite:** 783 tests, all green.

### Step L33: WorldMapMarker (2026-09-10)

**Red first:** `WorldMapMarkerTest`, over a mocked world-map point manager: a single target is
pinned and a multi-target set is not; the one-shot override pins the tile a set was expanded
from (the searched bank booth, not its walkable surround) and is consumed by that placement;
clear removes the pin. The class did not exist.

**Change:** the pin, its image, the one-shot override and the place/clear logic became
`WorldMapMarker`; the two target entry points ask for the override and the target setter places
or clears in one call. Plugin class: 1,962 to 1942 lines.

**Suite:** 786 tests, all green.

### Step L34: the balloon-log chat tracking on BalloonLogStorage (2026-09-10)

**Red first:** one case added to `BalloonLogStorageTest`: a storage line on a public channel
changes nothing; on the game channel it writes the parsed count and marks the storage synced
once; a later line writes its count without re-marking; with smart mode off nothing is
written. The method did not exist.

**Change:** the chat handler's body became `BalloonLogStorage.track` next to the parser it
called; the plugin's handler is the one call. Plugin class: 1,942 to 1924 lines.

**Suite:** 787 tests, all green.

### Step L35: RouteController (2026-09-10)

**Red first:** `RouteControllerTest`, over a mocked plugin, generator and config manager with a
real session, choice store and exclusions: a mode change saves the mode and regenerates once
with the last inputs, and the same mode again (or null) does nothing; opening the panel
re-checks the auto-compute decision, which fires once per target set and never before the
generator exists; show more widens both the route budget and the cost band before regenerating,
and only once a non-empty page settled below the cap. The class did not exist.

**Change:** the generation lifecycle became `RouteController`: the generator and its startup
and shutdown, the mode and its persistence, the catalog and unavailability snapshots, the
panel-visible flag and the page budget rule, trigger, stream and settle, the panel push, the
route pick, the catalog-only refresh (it now owns the `CatalogRefresher`), recompute and show
more. The plugin keeps the client-bound start tile, the round-trip wish and one-line public
delegates for the panel; the six tests that reached these members by reflection
(`AutoComputeDecisionTest`, `CatalogStutterHotfixTest`, `RoutingItemDependenciesTest`,
`ProvisionalDisplayLiveTest`, `ProvisionalDisplayTest`, `RouteRematchTest`) resolve the moved
names through a ROUTES_FIELDS map to the controller, the way SESSION_FIELDS did for L9, and call
its package-private methods directly instead of invoking private plugin methods. Plugin class:
1,924 to 1,721 lines.

**Suite:** 790 tests, all green.

### Step L36: DestinationController (2026-09-10)

**Red first:** `DestinationControllerTest`, over a mocked plugin, pathfinder config and
generator with a real session, route controller, marker, off-route tracker and journey: a pin
records where the player stands, the target, its source and a fresh one-way budget, arms the
journey (not started) and refreshes and filters the live config; a clear forgets everything and
keeps the catalog streaming; without a player position nothing changes; a round-trip category
keeps its flag past the target setter and recomputes at once with the flag, the next ordinary
pin drops it, and an empty category is ignored; find-closest appends the category's sites to
the current targets; a recalculation moves the start, drops the pick and regenerates. The class
did not exist.

**Change:** the destination state (start, targets, source, round trip) and every way of setting
it (map pin, searched place, amenity category, another plugin's request, find closest, clear,
the off-route recalculation) became `DestinationController`; the plugin keeps one-line public
delegates for the panel, the map menu and the message bridge, and reads the state through the
controller everywhere else (a tick-time clear now also drops the attribution, which only the
lingering arrival panel had already copied). The three display tests resolve the moved fields
through a DESTINATION_FIELDS map. Plugin class: 1,721 to 1,571 lines.

**Suite:** 796 tests, all green.

### CI: the csvlint step removed from the CSV Lint workflow (2026-09-10)

The CSV Lint workflow had failed on every push since N13 made the workflows run on main; the
CI Tests workflow was green throughout. The failing step was the third-party csvlint binary,
inherited from the upstream repository when the data was plain CSV: it validates RFC 4180 only
(one field count for every record, no comment or blank lines, no flag to skip either), which
the GPS TSV format breaks by design (comment headers, blank separators, omitted trailing cells
that `check_tsv.py` accepts since N13). Measured: 22 of the 40 files fail as they are, and six
would still fail with comments skipped. The project's own checker is the gate and stays; the
csvlint install and run steps are gone, with the reason in the workflow's header comment.

## Review follow-ups (2026-09-12)

The owner asked where the decomposition landed, what got worse, and which legacy systems remain
(the startup line "clearing stranded hidden toggle useWildernessObelisks" in particular), then
said to execute what quality and maintainability need. The review's numbers: plugin class 4,975
to 1,571 lines (code 3,773 to 1,055), 36 new classes, 679 to 796 tests; against that, the whole
main source grew 5.8% in comment-stripped code (delegates, accessors, javadoc), the panel still
talks to the plugin facade, seven tests reached plugin internals by reflection through three
name maps, and four collaborators take suppliers for injected services.

### Q1: HiddenToggleMigration, and why the log line recurred (2026-09-12)

**Finding:** RuneLite's `ConfigManager.setDefaultConfiguration` (bytecode of client 1.12.35, run
for every plugin at load) writes each default-method config item's default into the store when
the key is absent. The eighteen hidden type toggles default to on, so RuneLite wrote
`useWildernessObelisks=true`, the plugin's routine found a stored value, logged at INFO and
unset it, and the next start repeated the cycle: the owner's client log shows the eighteen lines
on 2026-09-09 and again on 2026-09-10. The routine's real job, clearing a stale `false` from a
pre-0.13 panel, is still valid for upgraders.

**Red first:** `HiddenToggleMigrationTest`: only a stored value differing from the default is
cleared (a stored "true" and an absent key are left alone; exactly one unset); every listed key
is a hidden config item keyed by its own name whose default method returns on (invoked through
a method handle on a proxy, so the DEFAULT constant cannot drift); the list never contains a
panel-backed toggle. `StrandedToggleMigrationTest`, which reached the plugin's private method by
reflection, is retired.

**Change:** the routine and its list became `HiddenToggleMigration.clearStranded`, comparing
against the default; the plugin's startUp makes the one call. Plugin class: 1,571 to 1546 lines.

**Suite:** 798 tests, all green.

### Q2: the dead dashboard workflow, and a stale comment (2026-09-12)

**Change:** the Dashboard Pages workflow ran on every CI Tests completion and was skipped every
time: its job was gated on a head branch named master, the same class of bug N13 fixed for the
other workflows. It also checks out the tooling repository, whose readability by the Actions
token is unverified (the network was down during the review), so switching the gate alone could
have turned every push red. It is manual-only now, with the gate corrected to main and the
reason in the file, until both conditions are confirmed. The "legacy getters" comment in
`TransportItems` misled: `getItems` has sixty call sites; it now says what the arrays are.

### Q3: the reflective tests target the controllers (2026-09-12)

**Change:** six tests reached plugin internals by reflection through three name maps left by
L9, L35 and L36. They now drive the real seams directly: `RouteRematchTest` settles and streams
a `RouteSession` (the generation's done-branch is one call to settle); `ProvisionalDisplayTest`
and `ProvisionalDisplayLiveTest` trigger and update a `RouteController` over a mocked plugin
(the live one still runs a real generation on real threads); the catalog cadence test in
`CatalogStutterHotfixTest` and the item-gate test in `RoutingItemDependenciesTest` use the
controller's new item hook. Three small production additions made that possible without
private access: `RouteSession.isFinding` (the HUD's finding state, which the plugin now
delegates), `RouteController.itemsChanged` (the fingerprint gate, moved out of the plugin's
item-container handler; it reports whether the catalog became dirty) with `isCatalogDirty`,
and `CatalogRefresher.isDirty`. The only test left reflecting into the plugin is the gated HUD
render dump, which sets injected overlay fields for a picture. Plugin class: 1,546 to 1541 lines.

**Suite:** 798 tests, all green.

## The panel out of one class (2026-09-12)

After the plugin class, the side panel (3,777 lines, 110 methods) is the largest file by far,
and the same criterion applies. The views come out cleanest seam first: the destination search,
the configuration sections, the method catalog, the route list and header; a shared widgets
class holds the chrome every section uses.

### Step P1: PanelWidgets and DestinationSearchView (2026-09-12)

**Red first:** `DestinationSearchRankingTest`: the match tier ranks before proximity (the
nearest entry, a word-prefix match, sits below the exact and prefix matches); proximity breaks
ties within a tier, the name when the player's position is unknown; at most twelve results;
nothing for no match. The ranking function did not exist (it was inline in the results render).
`FavoriteInputTest` now targets the view's coordinate parser.

**Change:** the "Go to" search (field, floating results popup, keyboard and mouse selection,
favourite editor, nearest-X row and menu, the search index cache) became
`DestinationSearchView`, a panel the main panel mounts and delegates focus and hide to. The
sizes, accent colours, dot colours, palette and the stateless builders every section uses
(centred cell, control label, subtle button, wrapped label, note row, dots, category colours,
HTML escaping, spacer) became `PanelWidgets`, statically imported where the panel still uses
them. Panel class: 3,777 to 2829 lines.

**Suite:** 801 tests, all green.

### Step P2: ConfigSectionsView (2026-09-12)

**Red first:** `ConfigSectionsViewTest`: the seconds chip reads in the route cards' sign
convention (a preference of +15 s shows as a green minus 15 s, neutral at zero); the balloon
chip says off, on or low logs; the spirit-tree chip says all (smart tracking off), on (not
synced), none or the planted count. The three chip functions were inline in the section
builders.

**Change:** the seven Travel-options sections (house, wilderness, walking, bank, balloons,
sailing, planted spirit trees), their expanded flags, the body, status, note and warning
helpers, the checkbox chrome, the icon rows and the Log-storage-low banner became
`ConfigSectionsView`; the panel's Travel section adds its sections in one loop and the notes
strip asks it for the balloon banner. The collapsible section shell and the two message-banner
builders, shared with the panel's Travel and catalog headers and the notes strip, moved to
`PanelWidgets` with the after-toggle rebuild passed in. Panel class: 2,829 to 1999 lines.

**Suite:** 804 tests, all green.

### Step P3: MethodCatalogView and TravelOptionsView (2026-09-12)

**Red first:** `MethodCatalogViewTest`: teleport items group by charge model and every other
method by its category; a banked item is usable only in the Inventory + bank mode; the funnel
filter keeps excluded methods or one unavailability kind and the text filter matches the
category or the label case-insensitively; every unavailability kind has a reason. The grouping,
usability and matching rules were inline in the row builders.

**Change:** the "Travel methods" catalog (the persistent filter box, the funnel menu, the
category headers with their include/exclude toggles, the item rows with their priority menus,
the bounded rows box and its scroll-position carry-over) became `MethodCatalogView`. The
"Travel options" slot that composes the configuration sections and the catalog under one
headline shell, with the rebuild-only-when-inputs-change rule, became `TravelOptionsView`; the
panel mounts it and asks it whether a render must rebuild. The scrollable box became its own
class, and the priority icons and tooltips, the method tooltip, the label joining, the click
recursion and the lock/bank status marker (all shared with the route cards) moved to
`PanelWidgets`. Panel class: 1,999 to 1224 lines.

**Suite:** 808 tests, all green.

### Step P4: RouteListView (2026-09-12)

**Red first:** `RouteListViewTest`: a duration reads as seconds under a minute and minutes
plus seconds above; the busy note switches to "longer routes" once the found routes span more
than three times the cheapest (three times exactly does not count), and never for an empty
list. Both rules were private to the panel. `EtaConsistencyTest` and `RouteEtaTest` now target
the view's ETA units.

**Change:** the routes section (the "Routes (N)" header with its more, refresh and clear
controls, the scrolling cards with pin, rank, ETA and priority chip, the bank-detour chip with
its client-thread tooltip, the method rows with their hover-revealed priority control, the walk
and sail totals, and the click-to-show selection) became `RouteListView`; the panel mounts it
and shows the current routes with the displayed one highlighted. Panel class: 1,224 to 660
lines.

**Suite:** 810 tests, all green.

### Step P5: PanelHeaderView and NoticesView (2026-09-12)

**Red first:** `NoticesViewTest`, the status banner's decision table: nothing while calculating
or while a route reaches; every route stopping short says why (the unlocks you lack, or no
known route); nothing found for a target hints at a broader mode unless already in All; an
empty list after arrival says arrived (or already there); otherwise no destination is set. The
decision was inline in the panel's render.

**Change:** the header (title row with Report an issue, GitHub, Discord and the burger; the
issue report's copy box; the segmented mode picker; the bank-contents warning under it) became
`PanelHeaderView`, and the notices strip (the status banner, the warnings behind their hide
toggle, the persistent dismiss, the arrival state) became `NoticesView`. The panel is now the
composition of its five views and one render that keeps them current, 169 lines from the
3,777 the series started at.

**Suite:** 814 tests, all green.

## Benchmarks that say what they measure (2026-09-12)

The question was why the benchmarks were not working. They were running; three things made them
look broken, and one of them measured the wrong thing.

### Step B1: the documented command, the generate benchmark's cache axis, probe output (2026-09-12)

**Red first:** none: build wiring and a JMH benchmark, neither under JUnit. The reproductions
were the documented command failing (`Task 'jmh' not found in root project 'gps'`) and the
generate numbers contradicting their own javadoc.

**Change:**

- Five of the eight benchmark javadocs said `./gradlew jmh --args=...` without the `-Pjmh`
  property that defines the JMH source set (gated so the hub build never sees it). The lines now
  carry the property, and the task exists without it too: a bare `./gradlew jmh` fails with the
  gate explained instead of "task not found".
- `GenerateBenchmark` claimed to measure a panel "find routes" click, field-build dominated at
  hundreds of milliseconds, but measured 3 to 14 ms for most scenarios: one service per trial fed
  the same query in a loop reuses the distance field since the cache of plan step N4, so it had
  become a warm-regeneration benchmark without anyone deciding that. A `cache` axis makes both
  explicit: `cold` builds a fresh service per invocation (the first click on a new target), `warm`
  keeps one per trial (walking toward a pinned target).
- The gated probes (`-Dgps.searchBench=true` and friends) print BENCH lines on stdout, which the
  test task hid; they only ever reached the XML and HTML reports. A run that forwards any probe
  flag now shows the tests' standard output. Scope such runs with `--tests`.

**Measured** (JMH, one fork, short iterations, 10 routes): lumbridge-barrows cold 188 ms, warm
14 ms; island cold 594 ms, warm 237 ms. The JMH fork runs on Gradle's JDK 21 while the client
runs on 11, so absolute numbers come from a different JIT than production.

**Suite:** 814 tests, all green.

### Step B2: benchmarks for the plugin's clicks, and a baseline (2026-09-12)

**Red first:** none: JMH benchmarks, a comparison script and documentation. The script was
smoke-tested on real JMH output (a run against itself compares clean; a perturbed copy is
flagged), and the new scenarios were checked to produce pages before the baseline run.

**Change:**

- `GenerateBenchmark` measures the plugin's clicks. Its `maxRoutes` axis measured a limit-1
  generation that production no longer runs (the route budget is the same with the panel shown
  or hidden, issue #18); a `mode` axis replaces it, `owned` being the plugin's default mode,
  which no benchmark covered. Four scenarios join the six: the panel's "nearest bank" (a
  map-wide target set), "nearest bank and back" (round trips), a water pin and a sealed target.
- The benchmark fixture used a stub config whose every setting was false, null or zero: no
  minigame teleports, zero-cost consumable teleports, a null item mode. It now uses the plugin's
  default settings (the implementing config the tests use). On Lumbridge to Barrows the stub
  found six single-method routes over a 230 ms field; the defaults fill a page of ten with
  multi-method chains over a 380 ms field, which is why the cold numbers roughly doubled against
  the previous run while the heuristic build and the guided search got cheaper.
- `ClassicPathfinderBenchmark` is deleted: nothing outside the service constructs a search any
  more, so it measured a path the plugin does not run (its uninformed reference lives on as
  PathfinderBenchmark's `uninformed` mode).
- `AvailabilityBenchmark` measured the rebuild with no exclusions, which hands the base lists
  back untouched (0 us/op). It now excludes one method, alternating between two: the per-search
  cost every chain iteration after the first pays.
- `docs/benchmarks/baseline.json` is a full run's output; `scripts/bench_compare.py` compares a
  fresh run against it and marks deltas beyond a threshold; `docs/Benchmarks.md` documents the
  set, how to run it (on JDK 11 too, by running Gradle on it) and the baseline with a reading.

**Measured** (the baseline, cold, owned mode): lumbridge-barrows 355 ms, capture 98 ms, island
602 ms, wilderness-escape 816 ms, nearest-bank 1045 ms, bank-and-back 1386 ms, water-pin 412 ms,
sealed 708 ms; warm 16 to 78 ms on the common queries. Full flood 271 ms, guided search 52 to
73 us, refresh 3.7 ms, rebuild with an exclusion 230 us.

**Suite:** main code untouched (benchmarks, script and docs only).
