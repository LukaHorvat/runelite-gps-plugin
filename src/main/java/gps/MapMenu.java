package gps;

import gps.pathfinder.PathStep;
import java.awt.Shape;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

/**
 * The right-click menu entries (plan step L22, out of the plugin class): "Set GPS Target" on a
 * shift-clicked tile and on the world map, "Clear Path" on the path, the map, the minimap, the
 * floating-map controls and the directions overlay, "Dismiss Arrival" on the overlay's lingering
 * "Arrived!" panel, and "Find closest" on a world-map icon whose kind GPS knows. A click hands
 * the pick to the plugin.
 */
final class MapMenu
{
	static final String CLEAR = "Clear";
	static final String DISMISS = "Dismiss";
	static final String ARRIVAL = ColorUtil.wrapWithColorTag("Arrival", JagexColors.MENU_TARGET);
	static final String PATH = ColorUtil.wrapWithColorTag("Path", JagexColors.MENU_TARGET);
	static final String SET = "Set";
	static final String FIND_CLOSEST = "Find closest";
	static final String FLASH_ICONS = "Flash icons";
	static final String TARGET = ColorUtil.wrapWithColorTag("GPS Target", JagexColors.MENU_TARGET);

	private final Client client;
	private final WorldMapProjection worldMap;
	private final MinimapClip minimapClip;
	private final ShortestPathPlugin plugin;
	// Where the menu opened: a world-map pick resolves against it, not the current mouse position.
	private Point lastMenuOpenedPoint;

	MapMenu(Client client, WorldMapProjection worldMap, MinimapClip minimapClip, ShortestPathPlugin plugin)
	{
		this.client = client;
		this.worldMap = worldMap;
		this.minimapClip = minimapClip;
		this.plugin = plugin;
	}

	void onMenuOpened()
	{
		lastMenuOpenedPoint = client.getMouseCanvasPosition();
	}

	void onMenuEntryAdded(MenuEntryAdded event)
	{
		addDirectionsEntry(event);
		if (client.isKeyPressed(KeyCode.KC_SHIFT) && event.getType() == MenuAction.WALK.getId())
		{
			addMenuEntry(event, SET, TARGET, 1);
			if (plugin.hasPathTargets())
			{
				int selectedTile = selectedWorldPoint();
				for (PathStep pathStep : plugin.getDisplayPath())
				{
					if (pathStep.getPackedPosition() == selectedTile)
					{
						addMenuEntry(event, CLEAR, PATH, 1);
						break;
					}
				}
			}
		}

		final Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map != null)
		{
			if (map.getBounds().contains(client.getMouseCanvasPosition().getX(), client.getMouseCanvasPosition().getY()))
			{
				addMenuEntry(event, SET, TARGET, 0);
				for (int target : plugin.getPathTargets())
				{
					if (target != WorldPointUtil.UNDEFINED)
					{
						addMenuEntry(event, CLEAR, PATH, 0);
					}
				}
			}
			if (event.getOption().equals(FLASH_ICONS)
				&& plugin.getPathfinderConfig().hasDestination(simplify(event.getTarget())))
			{
				addMenuEntry(event, FIND_CLOSEST, event.getTarget(), 1);
			}
		}

		final Shape minimap = minimapClip.area();
		if (minimap != null && plugin.hasPathTargets()
			&& minimap.contains(client.getMouseCanvasPosition().getX(), client.getMouseCanvasPosition().getY()))
		{
			addMenuEntry(event, CLEAR, PATH, 0);
		}
		if (minimap != null && plugin.hasPathTargets()
			&& ("Floating World Map".equals(Text.removeTags(event.getOption()))
			|| "Close Floating panel".equals(Text.removeTags(event.getOption()))))
		{
			addMenuEntry(event, CLEAR, PATH, 1);
		}
	}

	/**
	 * The directions overlay's own entry, on a plain right-click anywhere on the panel (RuneLite's
	 * built-in overlay menu needs shift): "Clear Path" while a route is shown or being found,
	 * "Dismiss Arrival" while the "Arrived!" panel lingers. The renderer zeroes the bounds of an
	 * overlay that drew nothing, so a hidden panel never matches.
	 */
	void addDirectionsEntry(MenuEntryAdded event)
	{
		RouteDirectionsOverlay directions = plugin.routeDirectionsOverlay();
		Point mouse = client.getMouseCanvasPosition();
		if (directions == null || !directions.getBounds().contains(mouse.getX(), mouse.getY()))
		{
			return;
		}
		if (directions.isArrivalShowing())
		{
			addMenuEntry(event, DISMISS, ARRIVAL, 0);
		}
		else if (plugin.hasPathTargets())
		{
			addMenuEntry(event, CLEAR, PATH, 0);
		}
	}

	private void onMenuOptionClicked(MenuEntry entry)
	{
		if (entry.getOption().equals(SET) && entry.getTarget().equals(TARGET))
		{
			plugin.pinTarget(selectedWorldPoint());
		}
		else if (entry.getOption().equals(CLEAR) && entry.getTarget().equals(PATH))
		{
			plugin.clearPinnedTarget();
		}
		else if (entry.getOption().equals(DISMISS) && entry.getTarget().equals(ARRIVAL))
		{
			plugin.routeDirectionsOverlay().dismissArrival();
			plugin.clearPinnedTarget();
		}
		else if (entry.getOption().equals(FIND_CLOSEST))
		{
			plugin.findClosest(simplify(entry.getTarget()));
		}
	}

	/** The tile under the menu: the selected scene tile, or the world-map tile where the menu opened. */
	private int selectedWorldPoint()
	{
		if (client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER) == null)
		{
			if (client.getTopLevelWorldView().getSelectedSceneTile() != null)
			{
				return WorldPointUtil.fromLocalInstance(client, client.getTopLevelWorldView().getSelectedSceneTile().getLocalLocation());
			}
			return WorldPointUtil.UNDEFINED;
		}
		return client.isMenuOpen()
			? worldMap.worldPointAt(lastMenuOpenedPoint.getX(), lastMenuOpenedPoint.getY())
			: worldMap.worldPointAt(client.getMouseCanvasPosition().getX(), client.getMouseCanvasPosition().getY());
	}

	private void addMenuEntry(MenuEntryAdded event, String option, String target, int position)
	{
		List<MenuEntry> entries = new LinkedList<>(Arrays.asList(client.getMenu().getMenuEntries()));
		if (entries.stream().anyMatch(e -> e.getOption().equals(option) && e.getTarget().equals(target)))
		{
			return;
		}
		client.getMenu().createMenuEntry(position)
			.setOption(option)
			.setTarget(target)
			.setParam0(event.getActionParam0())
			.setParam1(event.getActionParam1())
			.setIdentifier(event.getIdentifier())
			.setType(MenuAction.RUNELITE)
			.onClick(this::onMenuOptionClicked);
	}

	/** A world-map icon's menu target as a destination key: tags off, lower case, words joined by underscores. */
	static String simplify(String text)
	{
		return Text.removeTags(text).toLowerCase()
			.replaceAll("[^a-zA-Z ]", "")
			.replace(" ", "_")
			.replace("__", "_");
	}
}
