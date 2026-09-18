package gps;

import java.awt.Rectangle;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.MenuEntryAdded;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan step L22: the map menu, out of the plugin class. A world-map icon's menu target becomes
 * the destination key the transport data uses: colour tags stripped, lower case, letters and
 * spaces only, words joined by single underscores. The directions overlay gets its own entry on a
 * plain right-click: "Clear Path" while a route is up, "Dismiss Arrival" while the "Arrived!"
 * panel lingers, nothing when the mouse is off the panel or the panel is hidden.
 */
public class MapMenuTest
{
	@Test
	public void iconTargetsBecomeDestinationKeys()
	{
		assertEquals("grand_exchange", MapMenu.simplify("<col=ff9040>Grand Exchange</col>"));
		assertEquals("slayer_master_konar", MapMenu.simplify("Slayer Master (Konar)"));
		assertEquals("fairy_ring", MapMenu.simplify("Fairy ring"));
	}

	/** A client whose right-click menu is empty and whose mouse sits at (10, 10), with a fresh entry to fill. */
	private static Client clientWithMenu(MenuEntry created)
	{
		Client client = mock(Client.class);
		Menu menu = mock(Menu.class);
		when(client.getMenu()).thenReturn(menu);
		when(client.getMouseCanvasPosition()).thenReturn(new Point(10, 10));
		when(menu.getMenuEntries()).thenReturn(new MenuEntry[0]);
		when(menu.createMenuEntry(Mockito.anyInt())).thenReturn(created);
		return client;
	}

	private static MenuEntry fluentEntry()
	{
		return mock(MenuEntry.class, Mockito.RETURNS_SELF);
	}

	/** The directions overlay as drawn at (0, 0) with the given size, arrived or not. */
	private static ShortestPathPlugin pluginShowing(int width, int height, boolean arrived, boolean hasTargets)
	{
		ShortestPathPlugin plugin = mock(ShortestPathPlugin.class);
		RouteDirectionsOverlay overlay = mock(RouteDirectionsOverlay.class);
		when(overlay.getBounds()).thenReturn(new Rectangle(0, 0, width, height));
		when(overlay.isArrivalShowing()).thenReturn(arrived);
		when(plugin.routeDirectionsOverlay()).thenReturn(overlay);
		when(plugin.hasPathTargets()).thenReturn(hasTargets);
		return plugin;
	}

	@Test
	public void aRightClickOnTheDirectionsOverlayOffersClearPath()
	{
		MenuEntry created = fluentEntry();
		MapMenu menu = new MapMenu(clientWithMenu(created), null, null, pluginShowing(200, 100, false, true));
		menu.addDirectionsEntry(mock(MenuEntryAdded.class));
		verify(created).setOption(MapMenu.CLEAR);
		verify(created).setTarget(MapMenu.PATH);
		verify(created).setType(MenuAction.RUNELITE);
	}

	@Test
	public void aRightClickOnTheArrivedPanelOffersDismiss()
	{
		MenuEntry created = fluentEntry();
		MapMenu menu = new MapMenu(clientWithMenu(created), null, null, pluginShowing(200, 100, true, false));
		menu.addDirectionsEntry(mock(MenuEntryAdded.class));
		verify(created).setOption(MapMenu.DISMISS);
		verify(created).setTarget(MapMenu.ARRIVAL);
	}

	@Test
	public void noEntryOffThePanelOrWhenNothingIsShown()
	{
		MenuEntry created = fluentEntry();
		Client client = clientWithMenu(created);
		// The mouse at (10, 10) is outside a panel drawn 5 pixels wide.
		new MapMenu(client, null, null, pluginShowing(5, 5, false, true)).addDirectionsEntry(mock(MenuEntryAdded.class));
		// No route and no arrival: nothing to clear or dismiss.
		new MapMenu(client, null, null, pluginShowing(200, 100, false, false)).addDirectionsEntry(mock(MenuEntryAdded.class));
		verify(client.getMenu(), never()).createMenuEntry(Mockito.anyInt());
	}
}
