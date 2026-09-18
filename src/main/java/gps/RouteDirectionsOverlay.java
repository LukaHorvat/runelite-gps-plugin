package gps;

import com.google.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Movable "directions" panel for the route currently shown on the map: the numbered steps to follow
 * — walking legs, teleports/transports to use, bank withdrawals and climbs — with live progress from
 * the player's position. Completed steps grey out (and collapse into a summary line), the step being
 * executed is orange (amber once it's nearly finished), the next step is white. Drag it wherever you
 * like (RuneLite overlay editing); hidden when no route is shown or via the config toggle.
 */
public class RouteDirectionsOverlay extends OverlayPanel
{
	private static final int MAX_LINES = 14;
	// The panel grows to fit its widest line between these bounds; only beyond MAX_WIDTH do lines
	// get ellipsized (clipping "Use Rat Pits M…" is worse than a wider panel).
	private static final int MIN_WIDTH = 140;
	private static final int MAX_WIDTH = 360;
	// Component insets plus the gap between the left text and the right-aligned time.
	private static final int PANEL_PADDING = 18;

	private static final Color DONE = new Color(0x80, 0x80, 0x80);
	// The overlay's accent tone (map-app navigation blue by default, user-configurable): the
	// active step, header pin, divider rule and ETA badge. The lighter "about to end" shade and
	// the badge's border are derived from it each frame.
	private Color accent = new Color(0x4C, 0x8B, 0xF5);
	private Color accentEnding = lighten(accent, 0.35f);
	private static final Color NEXT = Color.WHITE;
	private static final Color UPCOMING = new Color(0xB4, 0xB4, 0xB4);
	private static final Color OFF_ROUTE = new Color(0xFF, 0x4C, 0x4C);

	// Magnifying-glass hierarchy: bold > regular > small, mapped from the text-size preset onto
	// combinations that render crisply (see OverlayFontSize — the pixel-art fonts only look right
	// at their native size 16 or exactly pixel-doubled).
	private OverlayFontSize fontSize = OverlayFontSize.NORMAL;
	private Font fontCurrent = FontManager.getRunescapeBoldFont();
	private Font fontNext = FontManager.getRunescapeFont();
	private Font fontOther = FontManager.getRunescapeSmallFont();

	private final Client client;
	private final ShortestPathPlugin plugin;

	// Progress along the displayed route: each frame the marker moves to the eligible path tile
	// nearest the player, and the ETA is (walk time to that tile + remaining route time from it).
	// It counts down as the player travels, grows again when backtracking or straying off the
	// path, and mid-ride transports are interpolated separately (see the riding branch).
	private RouteOption progressRoute;
	private int reachedIndex;
	// Remaining route time (ticks) from each path index, precomputed per route.
	private double[] remainingTicksAt;
	// The live remaining estimate chosen by the scoring pass.
	private double liveRemainingTicks;
	// Distance from the player to the tile the scoring pass selected: 0 = standing on the
	// path. Arrival is only trusted at 0 — anything else may be proximity through a wall.
	private int lastSelectionDistance = Integer.MAX_VALUE;

	// Straight-line proximity is not reachability: a later leg can pass close by across a cliff the
	// route detours around, and straight-line cost always underprices a transport that exists
	// because you can't walk there. Progress therefore moves INCREMENTALLY — a few indexes per
	// update, and only while genuinely near the line — with two jump exceptions: standing exactly ON
	// a path tile at walking speed (teleport/transport landings), and ride interpolation.
	// Progress-selection thresholds live in RouteProgress (the unit-testable tracker); this alias
	// remains for the vehicle-speed heuristic below.
	private static final int NEAR_DISTANCE = RouteProgress.NEAR_DISTANCE;
	/**
	 * Arrival radius for SAILING legs: the tracked position is the hull's anchor tile, and a
	 * boat stops short — hull clearance, quarter-tile velocity snapping, and the game halting
	 * adjacent to the target can leave the anchor several tiles out while the bow touches it.
	 */
	private static final int SEA_NEAR_DISTANCE = 12;
	// Walk-distance flood memo (see updateProgress): recomputed only when the player's tile changes.
	private java.util.Map<Integer, Integer> walkCache;
	private int walkCacheTile = WorldPointUtil.UNDEFINED;
	private static final double VEHICLE_TILES_PER_SECOND = 4.5;
	private static final long SPEED_SAMPLE_MILLIS = 400;
	private long speedSampleAt;
	private int speedSamplePosition = WorldPointUtil.UNDEFINED;
	private double speedTilesPerSecond;

	// Arrival lingering: the plugin clears the target the moment the destination is reached, which
	// would vanish the panel mid-glance. When the route disappears right after progress was at the
	// end, an "Arrived!" panel lingers instead — until clicked, or until the configurable
	// auto-dismiss timer runs out (when enabled).
	private static final long NEAR_END_GRACE_MILLIS = 4_000;
	private long nearEndAtMillis = Long.MIN_VALUE / 2;
	private boolean arrivalShowing;
	private long arrivalUntilMillis;
	// Snapshots for the arrival panel — the plugin clears the target (and its source) the moment the
	// destination is reached, so they must be captured while the route is still alive. The journey
	// start itself is owned by the plugin (movement-based), read via getJourneyStartMillis.
	private String arrivalSource;
	private long arrivalElapsedMillis;

	private static final Color ARRIVED = new Color(0x3C, 0xC8, 0x6A);

	@Inject
	public RouteDirectionsOverlay(Client client, ShortestPathPlugin plugin)
	{
		super(plugin);
		this.client = client;
		this.plugin = plugin;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.display().showDirections)
		{
			return null;
		}
		// Override RuneLite's overlay transparency on request: the base OverlayPanel only
		// substitutes the user's preferred colour when the panel still has the STANDARD
		// background, so setting our own (and restoring STANDARD when the override is off)
		// overrides or yields cleanly. The transparency spinner picks how see-through: the
		// standard colour's tone with 100% = fully invisible, 0% = solid. Every text row draws
		// with a shadow, so the panel stays readable on bare game background.
		panelComponent.setBackgroundColor(plugin.display().overrideOverlayTransparency
			? overriddenBackground(plugin.display().overlayTransparency)
			: ComponentConstants.STANDARD_BACKGROUND_COLOR);
		accent = plugin.display().colourOverlayAccent;
		accentEnding = lighten(accent, 0.35f);
		refreshFonts();
		long now = System.currentTimeMillis();
		RouteOption route = plugin.getDisplayedRoute();
		if (route == null)
		{
			// A fresh destination whose routes are still computing: the ground stays clear, the
			// HUD says so — a line that may still change is never shown as the route.
			if (plugin.isFindingRoute())
			{
				arrivalShowing = false;
				return renderFinding(graphics);
			}
			// The route just ended: if progress was at the destination moments ago, this is an
			// arrival — linger with a farewell instead of vanishing mid-glance.
			if (!arrivalShowing && now - nearEndAtMillis < NEAR_END_GRACE_MILLIS)
			{
				arrivalShowing = true;
				arrivalUntilMillis = plugin.display().arrivalAutoDismiss
					? now + plugin.display().arrivalDismissSeconds * 1000L : Long.MAX_VALUE;
				long journeyStart = plugin.getJourneyStartMillis();
				arrivalElapsedMillis = journeyStart == 0 ? 0 : Math.max(0, now - journeyStart);
			}
			nearEndAtMillis = Long.MIN_VALUE / 2;
			if (arrivalShowing && now < arrivalUntilMillis)
			{
				return renderArrival(graphics);
			}
			arrivalShowing = false;
			return null;
		}
		arrivalShowing = false;
		List<RouteDirections.Step> steps = plugin.getRouteDirections(route);
		if (steps.isEmpty())
		{
			return null;
		}
		updateProgress(route, steps);
		// "About to arrive" needs more than a small ETA — straight-line proximity can undercut
		// walls (the goal one tile away across a fence). It must be EARNED: standing exactly on
		// a path tile in the final stretch, with no unopened door left between here and the end.
		if (liveRemainingTicks <= 3.5 && lastSelectionDistance == 0 && lastLegClear(route, steps))
		{
			nearEndAtMillis = now;
		}

		// The first step whose span the player hasn't finished yet is the one being executed.
		int current = steps.size() - 1;
		for (int i = 0; i < steps.size(); i++)
		{
			if (!finished(steps.get(i)))
			{
				current = i;
				break;
			}
		}

		String etaText = "ETA: " + formatTime((int) Math.ceil(liveRemainingTicks * RouteDirections.SECONDS_PER_TICK));

		// First pass: collect the lines, then size the panel to the widest one (within bounds) so
		// content is neither wrapped nor needlessly clipped; only lines beyond MAX_WIDTH truncate.
		List<Line> lines = new ArrayList<>();
		String source = plugin.getTargetSource();
		arrivalSource = source;
		if (source != null)
		{
			lines.add(new Line("Destination set by " + source, fontOther, UPCOMING, null, null));
		}
		// Drifting off the drawn path: warn before the route is recomputed (the middle of the three
		// distance bands — see ShortestPathPlugin's off-route handling).
		if (plugin.isOffRouteWarning())
		{
			// With auto-recalculate off the route is deliberately kept, so don't promise a recompute.
			lines.add(new Line(plugin.isAutoRecalculateEnabled()
				? "Off route — recomputing if you drift further"
				: "Off route", fontNext, OFF_ROUTE, null, null));
		}

		// Window: collapse all but the most recent completed step into one summary line, then show
		// the current step and what follows, capped.
		int windowStart = Math.max(0, current - 1);
		if (windowStart > 0)
		{
			lines.add(new Line("✓ " + windowStart + (windowStart == 1 ? " step done" : " steps done"),
				fontOther, DONE, null, null));
		}
		int shown = 0;
		int i = windowStart;
		for (; i < steps.size() && shown < MAX_LINES; i++, shown++)
		{
			RouteDirections.Step step = steps.get(i);
			String stepText = i >= current && step.isDoor()
				? doorStepText(route, step) : step.getText();
			String text = (i + 1) + ". " + stepText;
			Color colour;
			Font font;
			if (i < current)
			{
				// The RuneScape fonts carry no U+2713 glyph, but the JVM's font pipeline substitutes
				// one from a system font (verified in-game on Windows). If reports of missing-glyph
				// boxes come in from other platforms, drop the prefix — the grey colour already
				// marks completion on its own.
				text = "✓ " + text;
				colour = DONE;
				font = fontOther;
			}
			else if (i == current)
			{
				colour = nearEnd(step) ? accentEnding : accent;
				font = fontCurrent;
			}
			else if (i == current + 1)
			{
				colour = NEXT;
				font = fontNext;
			}
			else
			{
				colour = UPCOMING;
				font = fontOther;
			}
			lines.add(new Line(text, font, colour,
				formatTime((int) Math.ceil(step.getTicks() * RouteDirections.SECONDS_PER_TICK)), colour));
			// The withdraw step's per-item list: indented, un-numbered, no own time.
			for (String detail : step.getDetails())
			{
				if (shown + 1 >= MAX_LINES)
				{
					break;
				}
				lines.add(new Line("      • " + detail, fontOther, colour, null, null));
				shown++;
			}
		}
		if (i < steps.size())
		{
			lines.add(new Line("… " + (steps.size() - i) + " more", fontOther, DONE, null, null));
		}

		Dimension dimension = renderPanel(graphics, lines);
		if (dimension != null)
		{
			drawEtaBadge(graphics, dimension, etaText);
		}
		return dimension;
	}

	/**
	 * Sizes the panel to its widest line (within bounds), emits the header spacer and the lines, and
	 * draws the decorated title over the reserved header row.
	 */
	private Dimension renderPanel(Graphics2D graphics, List<Line> lines)
	{
		// Fit the panel to the content: widest left text + its time column, clamped to sane bounds.
		// The bounds scale with the text-size preset (2x for Large) — at doubled glyph widths a
		// fixed cap would ellipsize twice as much of every step.
		final float widthScale = fontCurrent.getSize2D() / 16f;
		final int minWidth = Math.round(MIN_WIDTH * widthScale);
		final int maxWidth = Math.round(MAX_WIDTH * widthScale);
		int contentWidth = minWidth;
		for (Line line : lines)
		{
			// A centred line (arrival, finding) reads as a label, not a list row: give it the
			// margin on both sides, or the text hugs the panel edges.
			int width = graphics.getFontMetrics(line.font).stringWidth(line.left)
				+ rightWidth(graphics, line) + (line.centred ? 2 * PANEL_PADDING : PANEL_PADDING);
			contentWidth = Math.max(contentWidth, width);
		}
		int panelWidth = Math.min(contentWidth, maxWidth);
		panelComponent.setPreferredSize(new Dimension(panelWidth, 0));

		// Spacer reserving the header row; the decorated title (pin glyph + bold text + accent rule)
		// is custom-drawn over it after the panel renders — TitleComponent supports no font/styling.
		panelComponent.getChildren().add(
			LineComponent.builder()
				.left(" ")
				.leftFont(fontCurrent)
				.build());
		for (Line line : lines)
		{
			if (line.centred)
			{
				// Reserve the row with a spacer in the same font; the text itself is drawn
				// centred over the panel afterwards.
				panelComponent.getChildren().add(
					LineComponent.builder().left(" ").leftFont(line.font).build());
				continue;
			}
			LineComponent.LineComponentBuilder builder = LineComponent.builder()
				.left(ellipsize(graphics, line.font, line.left, panelWidth - rightWidth(graphics, line) - PANEL_PADDING))
				.leftColor(line.colour)
				.leftFont(line.font);
			if (line.right != null)
			{
				builder.right(line.right)
					.rightColor(line.rightColour)
					.rightFont(line.font == fontCurrent ? fontNext : fontOther);
			}
			panelComponent.getChildren().add(builder.build());
		}

		Dimension dimension = super.render(graphics);
		if (dimension != null)
		{
			drawTitle(graphics, dimension);
			drawCentredLines(graphics, dimension, lines);
		}
		return dimension;
	}

	/**
	 * Draws the trailing centred lines over their reserved spacer rows, anchored bottom-up to the
	 * panel's bottom edge. TextLayout's tight glyph bounds centre the text properly — the
	 * RuneScape fonts' metrics don't match their visual size (see {@link #drawEtaBadge}).
	 */
	private void drawCentredLines(Graphics2D graphics, Dimension panelSize, List<Line> lines)
	{
		int first = lines.size();
		while (first > 0 && lines.get(first - 1).centred)
		{
			first--;
		}
		float bottom = panelSize.height - 7;
		for (int i = lines.size() - 1; i >= first; i--)
		{
			Line line = lines.get(i);
			if (line.left.isBlank())
			{
				// A blank centred line is vertical air: its row is reserved in the panel, and the
				// text above it moves up by half that row (a full row would leave it floating).
				bottom -= line.font.getSize() / 2f + 5;
				continue;
			}
			TextLayout layout = new TextLayout(line.left, line.font, graphics.getFontRenderContext());
			Rectangle2D bounds = layout.getBounds();
			float x = (float) ((panelSize.width - bounds.getWidth()) / 2 - bounds.getX());
			// Place the glyph box's bottom on the anchor, then move the anchor up past it.
			float baseline = (float) (bottom - bounds.getHeight() - bounds.getY());
			drawLayout(graphics, layout, x, baseline, line.colour);
			bottom -= bounds.getHeight() + 5;
		}
	}

	/** Draws laid-out text with the stock one-pixel drop shadow (spacing-neutral in every mode). */
	private static void drawLayout(Graphics2D graphics, TextLayout layout, float x, float baseline, Color colour)
	{
		graphics.setColor(Color.BLACK);
		layout.draw(graphics, x + 1, baseline + 1);
		graphics.setColor(colour);
		layout.draw(graphics, x, baseline);
	}

	/**
	 * The overridden overlay background: the standard background's tone at the configured
	 * TRANSPARENCY (100% = invisible, 0% = solid), so a partially visible override still matches
	 * RuneLite's palette. Never null — a null would re-enable the base panel's colour handling.
	 */
	private static Color overriddenBackground(int transparencyPercent)
	{
		final int alpha = Math.max(0, Math.min(255, Math.round(255 * (100 - transparencyPercent) / 100f)));
		final Color standard = ComponentConstants.STANDARD_BACKGROUND_COLOR;
		return new Color(standard.getRed(), standard.getGreen(), standard.getBlue(), alpha);
	}

	/** Rebuilds the three fonts when the text-size preset changes. */
	private void refreshFonts()
	{
		final OverlayFontSize preset = plugin.display().overlayFontSize == null
			? OverlayFontSize.NORMAL : plugin.display().overlayFontSize;
		if (preset == fontSize)
		{
			return;
		}
		fontSize = preset;
		final Font bold = FontManager.getRunescapeBoldFont();
		final Font regular = FontManager.getRunescapeFont();
		final Font small = FontManager.getRunescapeSmallFont();
		switch (preset)
		{
			case SMALL:
				// The hierarchy shifted one tier down, every face at its native size.
				fontCurrent = regular;
				fontNext = small;
				fontOther = small;
				break;
			case LARGE:
				// The in-between step at 1.5x — a touch softer than the exact-multiple sizes.
				fontCurrent = bold.deriveFont(24f);
				fontNext = regular.deriveFont(24f);
				fontOther = small.deriveFont(24f);
				break;
			case EXTRA_LARGE:
				// Exactly pixel-doubled: the enlargement that keeps the pixel fonts sharp.
				fontCurrent = bold.deriveFont(32f);
				fontNext = regular.deriveFont(32f);
				fontOther = small.deriveFont(32f);
				break;
			default:
				fontCurrent = bold;
				fontNext = regular;
				fontOther = small;
				break;
		}
	}

	/** Blends a colour toward white — the derived "about to end" shade of the accent. */
	private static Color lighten(Color colour, float factor)
	{
		return new Color(
			colour.getRed() + Math.round((255 - colour.getRed()) * factor),
			colour.getGreen() + Math.round((255 - colour.getGreen()) * factor),
			colour.getBlue() + Math.round((255 - colour.getBlue()) * factor));
	}

	/**
	 * Decorated header drawn over the reserved top row: a small location-pin glyph, bold "GPS", and
	 * a navigation-blue rule under the header separating it from the steps.
	 */
	private void drawTitle(Graphics2D graphics, Dimension panelSize)
	{
		// All header geometry scales with the header font (1x at the native 16, 2x for Large).
		final float s = fontCurrent.getSize2D() / 16f;
		// Location pin: round head with a tail, hollow centre.
		final int px = 8;
		final int py = 4;
		graphics.setColor(accent);
		graphics.fillOval(px, py, Math.round(9 * s), Math.round(9 * s));
		Polygon tail = new Polygon(
			new int[]{px + Math.round(1 * s), px + Math.round(8 * s), px + Math.round(4 * s)},
			new int[]{py + Math.round(7 * s), py + Math.round(7 * s), py + Math.round(13 * s)},
			3);
		graphics.fillPolygon(tail);
		graphics.setColor(new Color(0x10, 0x10, 0x10));
		graphics.fillOval(px + Math.round(3 * s), py + Math.round(3 * s), Math.round(3 * s), Math.round(3 * s));

		graphics.setFont(fontCurrent);
		graphics.setColor(Color.BLACK);
		graphics.drawString("GPS", px + Math.round(14 * s), py + Math.round(12 * s));
		graphics.setColor(Color.WHITE);
		graphics.drawString("GPS", px + Math.round(13 * s), py + Math.round(11 * s));

		// Accent rule under the header row.
		graphics.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 170));
		graphics.drawLine(4, py + Math.round(15 * s), panelSize.width - 4, py + Math.round(15 * s));
	}

	/**
	 * The ETA as a floating pill overlapping the panel's top-right corner — a badge rather than a
	 * list row, map-app style.
	 */
	private void drawEtaBadge(Graphics2D graphics, Dimension panelSize, String etaText)
	{
		// TextLayout gives the glyphs' tight pixel bounds — the RuneScape fonts' metrics (ascent,
		// leading) don't match their visual size, which kept mis-centring the text in the pill.
		TextLayout layout = new TextLayout(etaText, fontOther, graphics.getFontRenderContext());
		Rectangle2D bounds = layout.getBounds();
		int width = (int) Math.ceil(bounds.getWidth()) + 12;
		int height = (int) Math.ceil(bounds.getHeight()) + 8;
		// Right edge pinned just inside the panel corner; the pill grows leftward as the countdown's
		// text widens. No overhang: with the overlay snapped to the screen edge, an overhanging pill
		// gets clipped and its text looks misaligned.
		int x = panelSize.width - width - 2;
		int y = 2;

		graphics.setColor(accent);
		graphics.fillRoundRect(x, y, width, height, 8, 8);
		graphics.setColor(accent.darker());
		graphics.drawRoundRect(x, y, width, height, 8, 8);
		// Centre the tight glyph box inside the pill on both axes.
		float textX = (float) (x + (width - bounds.getWidth()) / 2 - bounds.getX());
		float textY = (float) (y + (height - bounds.getHeight()) / 2 - bounds.getY());
		graphics.setColor(Color.BLACK);
		layout.draw(graphics, textX + 1, textY + 1);
		graphics.setColor(Color.WHITE);
		layout.draw(graphics, textX, textY);
	}

	/**
	 * One pending panel line: collected first so the panel can be sized to the widest line before
	 * any component is built.
	 */
	private static final class Line
	{
		private final String left;
		private final Font font;
		private final Color colour;
		private final String right;
		private final Color rightColour;
		// Centred lines are reserved as spacer rows and custom-drawn centred over the panel
		// afterwards — LineComponent only aligns left/right. Only trailing lines may be centred.
		private final boolean centred;

		private Line(String left, Font font, Color colour, String right, Color rightColour)
		{
			this(left, font, colour, right, rightColour, false);
		}

		private Line(String left, Font font, Color colour, String right, Color rightColour, boolean centred)
		{
			this.left = left;
			this.font = font;
			this.colour = colour;
			this.right = right;
			this.rightColour = rightColour;
			this.centred = centred;
		}
	}

	private int rightWidth(Graphics2D graphics, Line line)
	{
		return line.right == null ? 0 : graphics.getFontMetrics(fontOther).stringWidth(line.right) + 6;
	}

	/**
	 * Compact wall-time: "34s" under a minute, "2m 05s" above.
	 */
	private static String formatTime(int seconds)
	{
		if (seconds < 60)
		{
			return seconds + "s";
		}
		return (seconds / 60) + "m " + String.format("%02ds", seconds % 60);
	}

	/**
	 * Truncates a line to {@code maxWidth} instead of letting LineComponent wrap it — wrapped steps
	 * blur into their neighbours and break the list rhythm. With the panel sized to its content this
	 * only kicks in past {@link #MAX_WIDTH}.
	 */
	private static String ellipsize(Graphics2D graphics, Font font, String text, int maxWidth)
	{
		java.awt.FontMetrics metrics = graphics.getFontMetrics(font);
		if (metrics.stringWidth(text) <= maxWidth)
		{
			return text;
		}
		int end = text.length();
		while (end > 1 && metrics.stringWidth(text.substring(0, end) + "…") > maxWidth)
		{
			end--;
		}
		return text.substring(0, end) + "…";
	}

	/**
	 * Live wording for a pending door step: a door that already stands open needs walking
	 * through, not opening. Out-of-scene doors keep the "Open" default — regular doors shut
	 * themselves, so closed is the safe assumption until the door is visible.
	 */
	private String doorStepText(RouteOption route, RouteDirections.Step step)
	{
		List<gps.pathfinder.PathStep> path = route.getPath();
		if (step.getStartIndex() < 0 || step.getEndIndex() >= path.size())
		{
			return step.getText();
		}
		ClosedDoors.Door door = ClosedDoors.doorBetween(
			path.get(step.getStartIndex()).getPackedPosition(),
			path.get(step.getEndIndex()).getPackedPosition());
		if (door != null && ClosedDoors.state(client, door) == ClosedDoors.State.OPEN)
		{
			return "Go through " + door.name;
		}
		return step.getText();
	}

	/**
	 * Whether the current step is nearly finished: within the last fifth (or last 3 tiles) of its
	 * span. Only meaningful for longer spans — short steps (a teleport edge) flip states quickly
	 * anyway.
	 */
	private boolean nearEnd(RouteDirections.Step step)
	{
		int span = step.getEndIndex() - step.getStartIndex();
		if (span < 8)
		{
			return false;
		}
		int remaining = step.getEndIndex() - reachedIndex;
		return remaining <= Math.max(3, span / 5);
	}

	/**
	 * The lingering arrival panel: who set the destination, a green "Arrived!" and the total
	 * wall time the journey took. The whole panel is the dismiss button (see
	 * {@link #dismissArrivalAt}).
	 */
	/**
	 * The HUD while a fresh destination's routes compute: no route yet, just the label with a row
	 * of air above and below it. Static text on purpose — animated dots shift the centred label
	 * and the panel width a few pixels every beat, which reads as jitter, not progress.
	 */
	private Dimension renderFinding(Graphics2D graphics)
	{
		List<Line> lines = new ArrayList<>();
		String source = plugin.getTargetSource();
		if (source != null)
		{
			lines.add(new Line("Destination set by " + source, fontOther, UPCOMING, null, null));
		}
		lines.add(new Line(" ", fontOther, UPCOMING, null, null));
		lines.add(new Line("Finding the best route...", fontCurrent, NEXT, null, null, true));
		lines.add(new Line(" ", fontOther, NEXT, null, null, true));
		return renderPanel(graphics, lines);
	}

	private Dimension renderArrival(Graphics2D graphics)
	{
		List<Line> lines = new ArrayList<>();
		if (arrivalSource != null)
		{
			lines.add(new Line("Destination set by " + arrivalSource, fontOther, UPCOMING, null, null));
		}
		lines.add(new Line("Arrived!", fontCurrent, ARRIVED, null, null, true));
		lines.add(new Line("in " + formatTime((int) (arrivalElapsedMillis / 1000)), fontNext, NEXT, null, null, true));
		lines.add(new Line("(click to dismiss)", fontOther, DONE, null, null, true));
		return renderPanel(graphics, lines);
	}

	/**
	 * Dismisses the arrival panel when {@code point} (canvas coordinates) is inside it. Called from
	 * the plugin's mouse listener; returns true when the click was consumed.
	 */
	public boolean dismissArrivalAt(java.awt.Point point)
	{
		if (!arrivalShowing)
		{
			return false;
		}
		java.awt.Rectangle bounds = getBounds();
		if (bounds == null || !bounds.contains(point))
		{
			return false;
		}
		arrivalShowing = false;
		return true;
	}

	/** Hides the lingering "Arrived!" panel (the right-click menu's "Dismiss Arrival"). */
	public void dismissArrival()
	{
		arrivalShowing = false;
	}

	/** Whether the lingering "Arrived!" panel is what the overlay currently shows. */
	public boolean isArrivalShowing()
	{
		return arrivalShowing;
	}

	/**
	 * Latches the "Arrived!" panel. Called by the plugin the moment it clears the target on arrival,
	 * so arrival is shown even when the near-end proximity stamp never happened — e.g. a destination
	 * set while already at it, which the plugin clears on the very next tick before the route renders.
	 */
	public void markArrived(String source, long elapsedMillis)
	{
		arrivalSource = source;
		arrivalElapsedMillis = Math.max(0, elapsedMillis);
		arrivalShowing = true;
		long now = System.currentTimeMillis();
		arrivalUntilMillis = plugin.display().arrivalAutoDismiss
			? now + plugin.display().arrivalDismissSeconds * 1000L : Long.MAX_VALUE;
	}

	/**
	 * Moves the progress marker to the eligible path tile nearest the player, preferring the one
	 * closest to the previous position on ties — so standing where the path crosses itself doesn't
	 * teleport the highlight to the other pass. The ETA is then (walk time to that tile + remaining
	 * route time from it). No same-plane tile at all (e.g. an off-route dungeon detour) freezes the
	 * estimate.
	 */
	private void updateProgress(RouteOption route, List<RouteDirections.Step> steps)
	{
		List<gps.pathfinder.PathStep> path = route.getPath();
		if (route != progressRoute)
		{
			progressRoute = route;
			reachedIndex = 0;
			remainingTicksAt = buildRemainingTicks(route, steps);
			liveRemainingTicks = remainingTicksAt.length > 0 ? remainingTicksAt[0] : 0;
		}
		Player player = client.getLocalPlayer();
		if (player == null || remainingTicksAt.length != path.size())
		{
			return;
		}
		// Boat-aware: aboard, the raw local position is in the boat's sub-WorldView — the
		// tracker froze the moment the player boarded. UNDEFINED transients (view swaps)
		// simply skip this frame's update.
		int playerPacked = WorldPointUtil.fromLocalInstance(client, player);
		if (playerPacked == WorldPointUtil.UNDEFINED)
		{
			return;
		}
		int playerPlane = WorldPointUtil.unpackWorldPlane(playerPacked);

		// Rolling speed estimate (tiles/second): faster than any running player means a transport is
		// carrying us — freeze the estimate until we land instead of scoring transient positions.
		long now = System.currentTimeMillis();
		if (speedSamplePosition == WorldPointUtil.UNDEFINED || now - speedSampleAt >= SPEED_SAMPLE_MILLIS)
		{
			if (speedSamplePosition != WorldPointUtil.UNDEFINED && now > speedSampleAt)
			{
				// A plane change between samples reads as a big move (stairs/teleports).
				int moved = WorldPointUtil.unpackWorldPlane(speedSamplePosition) == playerPlane
					? WorldPointUtil.distanceBetween(speedSamplePosition, playerPacked)
					: NEAR_DISTANCE * 2;
				speedTilesPerSecond = moved * 1000.0 / (now - speedSampleAt);
			}
			speedSampleAt = now;
			speedSamplePosition = playerPacked;
		}
		// Speed alone cannot see a slow boat: 2 tiles/tick equals running speed, so the
		// vehicle threshold never trips aboard and the ETA froze (field report at sea). The
		// boarded varbit says it plainly.
		boolean riding = speedTilesPerSecond > VEHICLE_TILES_PER_SECOND || aboard();

		if (riding)
		{
			// Mid-transport: interpolate through the ride geometrically. Distance to the landing
			// tile over the full hop length gives the fraction completed; the ETA becomes the
			// remainder of the ride plus everything after it. Progress itself (step completion)
			// still only advances on landing — and mid-flight never counts as standing on the
			// path, so a ride passing over the destination can't stamp an arrival.
			lastSelectionDistance = Integer.MAX_VALUE;
			RouteDirections.Step ride = currentStep(plugin.getRouteDirections(route));
			// Aboard, the ride is found by GEOMETRY: boarding happens from range, so the
			// boarding node may never have been stood on — currentStep would stay the walk
			// leg forever, freezing the list at "Walk" and never stamping the arrival. The
			// sail step whose cached track passes nearest the boat is the active one; being
			// aboard also means everything before its departure node is done.
			if (aboard())
			{
				RouteDirections.Step sail = activeSailRide(
					route, plugin.getRouteDirections(route), playerPacked);
				if (sail != null)
				{
					ride = sail;
					reachedIndex = Math.max(reachedIndex, sail.getStartIndex());
				}
			}
			if (ride != null && ride.isTransport())
			{
				int origin = path.get(Math.max(0, ride.getStartIndex())).getPackedPosition();
				int destination = path.get(ride.getEndIndex()).getPackedPosition();
				// Sailing rides follow a CURVED track: the embarked world position scores
				// directly against the leg's own waypoints (no walls at sea, so straight-line
				// to the track IS the walk metric — the collision BFS the land selector uses
				// cannot flood a sealed ocean). Progress advances waypoint by waypoint; the
				// last waypoint, or proximity to the landing, stamps the arrival — that is
				// what greys the sailed stretch and completes water-pin routes.
				boolean sailingRide = route.sailingJumpDepartures().contains(ride.getStartIndex());
				// Proximity to the landing IS arrival — checked before anything track-based,
				// because the live learner invalidates the track cache exactly when arriving
				// at an obstacle-rich port, and the stamp must not depend on a cache.
				if (sailingRide && WorldPointUtil.distanceBetween(playerPacked, destination)
					<= SEA_NEAR_DISTANCE)
				{
					reachedIndex = Math.max(reachedIndex, ride.getEndIndex());
					liveRemainingTicks = remainingTicksAt[ride.getEndIndex()];
					// The completion banner gates on selection distance ZERO — a land-selector
					// concept the riding branch pins to MAX_VALUE. Sea arrival IS selection.
					lastSelectionDistance = 0;
					return;
				}
				int[] track = sailingRide ? SailingSea.seaPath(origin, destination) : null;
				if (track != null && track.length > 1)
				{
					int nearest = 0;
					int nearestDistance = Integer.MAX_VALUE;
					for (int w = 0; w < track.length; w++)
					{
						int d = WorldPointUtil.distanceBetween(playerPacked, track[w]);
						if (d < nearestDistance)
						{
							nearestDistance = d;
							nearest = w;
						}
					}
					// Last FEW waypoints, not the literal last: waypoints are decimated
					// (every 3rd tile) and the hull parks short of the pin.
					if (nearest >= track.length - 3
						|| WorldPointUtil.distanceBetween(playerPacked, destination) <= SEA_NEAR_DISTANCE)
					{
						reachedIndex = Math.max(reachedIndex, ride.getEndIndex());
						liveRemainingTicks = remainingTicksAt[ride.getEndIndex()];
						lastSelectionDistance = 0;
					}
					else
					{
						double completed = nearest / (double) (track.length - 1);
						liveRemainingTicks = remainingTicksAt[ride.getEndIndex()]
							+ rideTicks(ride) * (1 - completed);
					}
				}
				else if (WorldPointUtil.unpackWorldPlane(destination) == playerPlane)
				{
					double total = WorldPointUtil.distanceBetween(origin, destination);
					if (total > 4)
					{
						double completed = Math.min(1,
							1 - WorldPointUtil.distanceBetween(playerPacked, destination) / total);
						liveRemainingTicks = remainingTicksAt[ride.getEndIndex()]
							+ rideTicks(ride) * (1 - Math.max(0, completed));
					}
				}
			}
			return;
		}

		// The first uncrossed door ahead gates progress: straight-line proximity sees through a
		// closed door (path tiles beyond it are physically a wall's width away — even adjacent,
		// from the near doorway tile), so several door steps used to complete at once while
		// still outside. Anything at or past that edge only counts once the player is standing
		// exactly on the path beyond it, which a one-tile doorway forces anyway.
		int doorGate = Integer.MAX_VALUE;
		for (RouteDirections.Step step : steps)
		{
			if (step.isDoor() && step.getEndIndex() > reachedIndex)
			{
				doorGate = step.getEndIndex();
				break;
			}
		}

		// Round trips: the return leg often retraces the outbound street, so its tiles match the
		// player's position from the very first step OUT. Until the turnaround (the destination)
		// has been reached, return-leg indexes are ineligible — otherwise walking out reads as
		// coming back and the route completes instantly.
		int turnaround = route.getTurnaroundIndex();
		int returnGate = (turnaround >= 0 && reachedIndex < turnaround - 2)
			? turnaround : Integer.MAX_VALUE;

		// Progress = the eligible path tile NEAREST the player by WALKING distance (ties broken
		// toward the current position) — see RouteProgress for the wall-aware maze rules; the
		// selection logic lives there so it is unit-testable. The ETA is derived from the selected
		// tile afterwards, keeping its walk-back charge without steering the selection.
		// The BFS is memoized by player tile: render runs per FRAME, but the flood only changes
		// when the player's tile does (game movement is tick-paced) — ~2 floods/s instead of ~100.
		if (playerPacked != walkCacheTile)
		{
			walkCache = RouteProgress.walkDistances(
				plugin.getCollisionMap(), playerPacked, RouteProgress.REACH_RADIUS);
			walkCacheTile = playerPacked;
		}
		RouteProgress.Result selection = RouteProgress.select(
			path, reachedIndex, doorGate, returnGate, playerPacked, walkCache);
		if (selection == null)
		{
			// Nowhere near the route (long off-path detour): hold the last honest estimate.
			return;
		}
		reachedIndex = selection.index;
		liveRemainingTicks = selection.distance / 2.0 + remainingTicksAt[selection.index];
		lastSelectionDistance = selection.distance;
	}

	/**
	 * Whether the remaining leg is clear of doors that haven't been seen open — the "green"
	 * condition for arrival: a small ETA behind a still-closed (or unverified) door is not
	 * an arrival.
	 */
	private boolean lastLegClear(RouteOption route, List<RouteDirections.Step> steps)
	{
		List<gps.pathfinder.PathStep> path = route.getPath();
		for (RouteDirections.Step step : steps)
		{
			if (!step.isDoor() || step.getEndIndex() <= reachedIndex || step.getEndIndex() >= path.size())
			{
				continue;
			}
			ClosedDoors.Door door = ClosedDoors.doorBetween(
				path.get(step.getStartIndex()).getPackedPosition(),
				path.get(step.getEndIndex()).getPackedPosition());
			if (door != null && ClosedDoors.state(client, door) != ClosedDoors.State.OPEN)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Progress state exposed for the debug snapshot.
	 */
	int getReachedIndex()
	{
		return reachedIndex;
	}

	/**
	 * The tracked progress along {@code route}, or 0 when that route isn't the one being
	 * tracked (different selection, or nothing tracked yet).
	 */
	int reachedIndexFor(RouteOption route)
	{
		return route == progressRoute ? reachedIndex : 0;
	}

	double getLiveRemainingTicks()
	{
		return liveRemainingTicks;
	}

	double getSpeedTilesPerSecond()
	{
		return speedTilesPerSecond;
	}

	/**
	 * The step the player is currently executing: the first whose span isn't finished.
	 */
	private RouteDirections.Step currentStep(List<RouteDirections.Step> steps)
	{
		for (RouteDirections.Step step : steps)
		{
			if (!finished(step))
			{
				return step;
			}
		}
		return steps.isEmpty() ? null : steps.get(steps.size() - 1);
	}

	/**
	 * A step is finished once the player has passed its last path node — EXCEPT the boarding
	 * half of a sailing leg, which shares its node with the walk that leads there: standing on
	 * the dock is not boarding, only the BOARDED varbit finishes it.
	 */
	private boolean finished(RouteDirections.Step step)
	{
		if (step.getEndIndex() > reachedIndex)
		{
			return false;
		}
		return !step.isEmbark() || aboard();
	}

	/**
	 * The sailing ride step whose cached sea track passes nearest the aboard player (within
	 * 30 tiles), or null: multiple sail legs in one route disambiguate by geometry.
	 */
	private RouteDirections.Step activeSailRide(RouteOption route,
		List<RouteDirections.Step> steps, int playerPacked)
	{
		List<gps.pathfinder.PathStep> path = route.getPath();
		RouteDirections.Step best = null;
		int bestDistance = 30;
		for (RouteDirections.Step step : steps)
		{
			if (!step.isTransport() || step.getEndIndex() >= path.size()
				|| !route.sailingJumpDepartures().contains(step.getStartIndex()))
			{
				continue;
			}
			int origin = path.get(Math.max(0, step.getStartIndex())).getPackedPosition();
			int destination = path.get(step.getEndIndex()).getPackedPosition();
			int[] track = SailingSea.seaPath(origin, destination);
			if (track == null)
			{
				// Track cache just invalidated (the live learner clears it precisely at
				// obstacle-rich ports — i.e. at ARRIVAL): endpoints still identify the leg.
				int d = Math.min(WorldPointUtil.distanceBetween(playerPacked, origin),
					WorldPointUtil.distanceBetween(playerPacked, destination));
				if (d < bestDistance)
				{
					bestDistance = d;
					best = step;
				}
				continue;
			}
			for (int waypoint : track)
			{
				int d = WorldPointUtil.distanceBetween(playerPacked, waypoint);
				if (d < bestDistance)
				{
					bestDistance = d;
					best = step;
				}
			}
		}
		return best;
	}

	/** Whether the player is at the helm (SAILING_BOARDED_BOAT). */
	private boolean aboard()
	{
		return client.getVarbitValue(net.runelite.api.gameval.VarbitID.SAILING_BOARDED_BOAT) != 0;
	}

	/**
	 * Remaining route time (ticks) from each path index. When the path carries the search's
	 * cumulative costs (plan step N9) the table is exact: {@code (cost[last] - cost[i]) / 2}, the
	 * same number the route card shows in seconds. Otherwise: total of all later steps plus the
	 * linear remainder of the step spanning the index. Index 0 holds the whole journey.
	 */
	/** A ride's duration as the ETA table sees it, so mid-ride interpolation stays on the same line. */
	private double rideTicks(RouteDirections.Step ride)
	{
		int start = Math.max(0, Math.min(ride.getStartIndex(), remainingTicksAt.length - 1));
		int end = Math.max(start, Math.min(ride.getEndIndex(), remainingTicksAt.length - 1));
		return Math.max(0, remainingTicksAt[start] - remainingTicksAt[end]);
	}

	static double[] buildRemainingTicks(RouteOption route, List<RouteDirections.Step> steps)
	{
		List<gps.pathfinder.PathStep> path = route.getPath();
		int pathSize = path.size();
		boolean costed = pathSize > 0;
		for (gps.pathfinder.PathStep step : path)
		{
			if (step.getCost() == gps.pathfinder.PathStep.UNKNOWN_COST)
			{
				costed = false;
				break;
			}
		}
		if (costed)
		{
			double[] exact = new double[pathSize];
			int end = path.get(pathSize - 1).getCost();
			for (int i = 0; i < pathSize; i++)
			{
				exact[i] = Math.max(0, end - path.get(i).getCost()) / (double) gps.pathfinder.CostUnits.UNITS_PER_TICK;
			}
			return exact;
		}
		double[] remaining = new double[pathSize];
		double after = 0;
		for (int s = steps.size() - 1; s >= 0; s--)
		{
			RouteDirections.Step step = steps.get(s);
			int start = Math.max(0, Math.min(step.getStartIndex(), pathSize - 1));
			int end = Math.max(start, Math.min(step.getEndIndex(), pathSize - 1));
			int span = Math.max(1, end - start);
			for (int i = end; i >= start; i--)
			{
				double throughStep = step.getTicks() * ((end - i) / (double) span);
				remaining[i] = Math.max(remaining[i], after + throughStep);
			}
			after += step.getTicks();
		}
		return remaining;
	}
}
