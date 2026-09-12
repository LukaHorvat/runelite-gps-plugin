package gps.pathfinder;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import gps.Destinations;
import gps.TestShortestPathConfig;
import gps.WorldPointUtil;

/**
 * Shared setup for the search-pipeline benchmarks (heuristic build, search, availability, generate).
 * Builds a planning config off the real collision map + transport data with the plugin's default
 * settings, and resolves representative start/target queries of increasing difficulty, each the
 * kind of click a player makes.
 */
final class BenchScenarios
{
	// Cross-country, largely walking with a few transports.
	private static final int LUMBRIDGE = WorldPointUtil.packWorldPoint(3222, 3218, 0);
	private static final int BARROWS = WorldPointUtil.packWorldPoint(3566, 3291, 0);
	// Needs a real teleport/boat leg to another region.
	private static final int GRAND_EXCHANGE = WorldPointUtil.packWorldPoint(3164, 3487, 0);
	private static final int SHILO_VILLAGE = WorldPointUtil.packWorldPoint(2852, 2954, 0);
	// The capture query: Lumbridge to the Varlamore Hunter Guild, a cross-continent multi-teleport.
	private static final int CAPTURE_START = WorldPointUtil.packWorldPoint(3219, 3219, 0);
	private static final Set<Integer> CAPTURE_TARGETS = Set.of(
		WorldPointUtil.packWorldPoint(1556, 3046, 0),
		WorldPointUtil.packWorldPoint(1555, 3045, 0),
		WorldPointUtil.packWorldPoint(1555, 3046, 0),
		WorldPointUtil.packWorldPoint(1554, 3046, 0));
	// Uncommon shapes (see UncommonPathShapesTest): boat-only island, deep wilderness both ways.
	private static final int ENTRANA = WorldPointUtil.packWorldPoint(2830, 3335, 0);
	private static final int DEEP_WILDERNESS = WorldPointUtil.packWorldPoint(3004, 3937, 0);
	private static final int VARROCK = WorldPointUtil.packWorldPoint(3213, 3424, 0);
	// A water pin off Dognose (see SailingGenerationTest): the sea legs are synthesized per
	// generation and the reverse field must see them.
	private static final int DOGNOSE_PIN = WorldPointUtil.packWorldPoint(3048, 2648, 0);
	// The Broken Raft deck: open tiles ringed by the river, never reachable. The provably
	// unreachable short circuit (plan step N7): one complete flood, three escape routes, and in
	// an owned mode the all-everything probe behind the "not with what you have" verdict.
	private static final int LUM_RAFT = WorldPointUtil.packWorldPoint(3253, 3180, 0);

	private BenchScenarios()
	{
	}

	/**
	 * A planning copy with the plugin's default settings. A real implementing config, so every
	 * unoverridden setting resolves to the interface's default (sailing on, as shipped); the
	 * stub mock this used to be returned false for every toggle. Everything-mode until a
	 * generation applies its own mode on the client-thread refresh.
	 */
	static PathfinderConfig everythingConfig()
	{
		TestShortestPathConfig cfg = new TestShortestPathConfig();
		cfg.setCalculationCutoffValue(120);
		PathfinderConfig config = new TestPathfinderConfig(realisticClient(), cfg).copyForPlanning();
		config.refresh();
		return config;
	}

	/**
	 * A real (non-Mockito) Client. refresh() makes thousands of getVarbitValue calls, and even a
	 * stubOnly Mockito mock allocates a transient invocation on every one; that alone made refresh
	 * measure ~15x slower and ~70x heavier than it is with a real client. A reflective Proxy returning
	 * game-state defaults is a far more representative (and still conservative) stand-in: empty
	 * containers, so an owned mode sees a player carrying nothing.
	 */
	private static Client realisticClient()
	{
		final Thread clientThread = Thread.currentThread();
		return (Client) Proxy.newProxyInstance(
			Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState":
						return GameState.LOGGED_IN;
					case "getClientThread":
						return clientThread;
					case "getBoostedSkillLevel":
						return 99;
					default:
						return defaultValue(method.getReturnType());
				}
			});
	}

	private static Object defaultValue(Class<?> type)
	{
		if (type == int.class || type == short.class || type == byte.class)
		{
			return 0;
		}
		if (type == long.class)
		{
			return 0L;
		}
		if (type == boolean.class)
		{
			return false;
		}
		if (type == double.class)
		{
			return 0.0;
		}
		if (type == float.class)
		{
			return 0f;
		}
		if (type == char.class)
		{
			return (char) 0;
		}
		return null;
	}

	static int start(String scenario)
	{
		switch (scenario)
		{
			case "lumbridge-barrows":
			case "island":
			case "deep-wild":
			case "nearest-bank":
			case "bank-and-back":
			case "water-pin":
			case "sealed":
				return LUMBRIDGE;
			case "ge-shilo":
				return GRAND_EXCHANGE;
			case "capture":
				return CAPTURE_START;
			case "wilderness-escape":
				return DEEP_WILDERNESS;
			default:
				throw new IllegalArgumentException("unknown scenario: " + scenario);
		}
	}

	/** The target set; the nearest-bank scenarios gather the bank tiles the way the panel does. */
	static Set<Integer> targets(String scenario, PathfinderConfig config)
	{
		switch (scenario)
		{
			case "lumbridge-barrows":
				return Set.of(BARROWS);
			case "ge-shilo":
				return Set.of(SHILO_VILLAGE);
			case "capture":
				return CAPTURE_TARGETS;
			case "island":
				return Set.of(ENTRANA);
			case "deep-wild":
				return Set.of(DEEP_WILDERNESS);
			case "wilderness-escape":
				return Set.of(VARROCK);
			case "nearest-bank":
			case "bank-and-back":
			{
				// The panel's "nearest bank": the amenity dump's bank tiles plus the engine's own
				// accessible-bank tiles (see DestinationSearchView.runNearestOption). A map-wide
				// set: no compact field, so every search runs uninformed under the walk cap.
				Set<Integer> banks = new HashSet<>(Destinations.tilesForCategory("bank", null));
				banks.addAll(config.getDestinations("bank"));
				return banks;
			}
			case "water-pin":
				return Set.of(DOGNOSE_PIN);
			case "sealed":
				return Set.of(LUM_RAFT);
			default:
				throw new IllegalArgumentException("unknown scenario: " + scenario);
		}
	}

	/** "Nearest bank and back" is the one round-trip destination the panel offers. */
	static boolean roundTrip(String scenario)
	{
		return "bank-and-back".equals(scenario);
	}
}
