package com.entropymod.client.gui;

import com.entropymod.entropy.EffectPhase;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.network.ChoiceMadePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The real player-facing GUI for a pick. Shows 3 panels (image slot + title +
 * description); clicking anywhere on a panel sends the choice back to the
 * server and closes the screen. This screen intentionally has no close button
 * and no escape-to-cancel -- picking is mandatory, matching the "you must
 * choose" design.
 *
 * <p>API NOTE (supersedes the older "best guess" comment that used to live
 * here): every Minecraft symbol in this file was verified by javap against
 * {@code minecraft-clientonly-deobf-26.1.2.jar}, not guessed from docs:
 * <ul>
 *   <li>{@code GuiGraphicsExtractor} really is in {@code net.minecraft.client.gui},
 *       and exposes {@code fill/fillGradient/text/centeredText/pose/enableScissor}.</li>
 *   <li>{@code fill(x1,y1,x2,y2,argb)} is corner-to-corner, NOT (x,y,w,h) --
 *       its bytecode swaps the pair when they are out of order. All panel
 *       chrome here is built from {@code fill} alone, deliberately, because it
 *       is the one primitive whose argument order is confirmed.</li>
 *   <li>{@code fill} colours need an explicit alpha byte. {@code 0x707070}
 *       (alpha 0) draws nothing. Every colour constant below is full ARGB.</li>
 *   <li>Mouse handling changed in this version: {@code mouseClicked} now takes
 *       {@code (MouseButtonEvent, boolean)}, not {@code (double,double,int)}.
 *       {@link AbstractWidget#mouseClicked} does hit-testing then calls
 *       {@code onClick}, so a full-panel-sized widget gets whole-panel clicks
 *       plus the vanilla click sound for free.</li>
 * </ul>
 *
 * <p>Layout, colour and description-sizing maths live in dependency-free
 * static nested classes ({@link EntropyPalette}, {@link PanelLayout},
 * {@link #resolveDescriptionStyle}) so they can be exercised by a plain JVM
 * harness without booting Minecraft. See the commit that introduced this file.
 */
public class ChoiceScreen extends Screen {

	// ---------------------------------------------------------------------
	// Layout constants
	// ---------------------------------------------------------------------

	private static final int SCREEN_MARGIN = 12;
	private static final int PANEL_MAX_WIDTH = 160;
	private static final int PANEL_MIN_WIDTH = 90;
	private static final int PANEL_GAP = 20;
	/** Cap on a stacked row's width so rows don't stretch absurdly wide. */
	private static final int ROW_MAX_WIDTH = 260;
	private static final int ROW_GAP = 6;

	private static final int BORDER = 3;
	private static final int PANEL_PADDING = 6;
	private static final int IMAGE_SIZE_COLUMNS = 64;
	private static final int IMAGE_SIZE_ROWS = 32;
	private static final int ACCENT_RULE_HEIGHT = 2;
	private static final int ACCENT_RULE_MAX_HALF_WIDTH = 180;

	// ---------------------------------------------------------------------
	// Description sizing
	// ---------------------------------------------------------------------

	/**
	 * If ANY of the three descriptions in a pick is longer than this, ALL three
	 * panels switch to compact mode together. Keeping the three panels
	 * identical matters more than sizing each one to its own text.
	 */
	private static final int DESCRIPTION_COMPACT_THRESHOLD_CHARS = 45;
	private static final float COMPACT_TEXT_SCALE = 0.75f;
	private static final int DESCRIPTION_COMPACT_MAX_LINES = 2;
	private static final int DESCRIPTION_NORMAL_MAX_LINES = 3;

	// ---------------------------------------------------------------------
	// Colours (full ARGB -- see class javadoc)
	// ---------------------------------------------------------------------

	private static final int PANEL_FILL = 0xFF707070;
	private static final int PANEL_FILL_HOVER = 0xFF828282;
	private static final int BEVEL_HIGHLIGHT = 0xFF9A9A9A;
	private static final int BEVEL_SHADOW = 0xFF4A4A4A;
	private static final int IMAGE_SLOT_FILL = 0xFF3A3A3A;
	private static final int IMAGE_SLOT_INSET = 0xFF2A2A2A;
	private static final int IMAGE_SLOT_DASH = 0xFF8C8C8C;
	private static final int DESCRIPTION_COLOR = 0xFFC8C8C8;
	/** Header stays pure white on purpose -- legibility beats mood-matching. */
	private static final int HEADER_COLOR = 0xFFFFFFFF;

	// ---------------------------------------------------------------------
	// State
	// ---------------------------------------------------------------------

	private final EffectPhase phase;
	private final int entropy;
	private final int entropyCap;
	private final List<Choice> choices;

	/** Recomputed in init(); read by extractRenderState. */
	private int accentColor = HEADER_COLOR;
	private int headerTextY;
	private int accentRuleY;

	private record Choice(String id, String name, String description) {}

	/**
	 * Convenience constructor used by the live network path, which currently
	 * has no way to learn the server's real entropy cap ({@code OpenChoicePayload}
	 * carries phase + entropy only). Falls back to the default cap so the colour
	 * ramp is still correct for the default configuration; if the cap is ever
	 * added to the payload, call the 12-arg constructor instead and nothing else
	 * here needs to change.
	 */
	public ChoiceScreen(EffectPhase phase, int entropy,
						 String id1, String name1, String desc1,
						 String id2, String name2, String desc2,
						 String id3, String name3, String desc3) {
		this(phase, entropy, EntropyManager.DEFAULT_ENTROPY_CAP,
				id1, name1, desc1, id2, name2, desc2, id3, name3, desc3);
	}

	public ChoiceScreen(EffectPhase phase, int entropy, int entropyCap,
						 String id1, String name1, String desc1,
						 String id2, String name2, String desc2,
						 String id3, String name3, String desc3) {
		super(Component.literal(phase == EffectPhase.GOOD ? "Choose a Blessing" : "Choose a Curse"));
		this.phase = phase;
		this.entropy = entropy;
		this.entropyCap = entropyCap;
		this.choices = List.of(
				new Choice(id1, name1, desc1),
				new Choice(id2, name2, desc2),
				new Choice(id3, name3, desc3));
	}

	// ---------------------------------------------------------------------
	// Layout
	// ---------------------------------------------------------------------

	@Override
	protected void init() {
		this.accentColor = EntropyPalette.accent(phase == EffectPhase.GOOD, entropy, entropyCap);

		PanelLayout layout = PanelLayout.resolve(this.width);
		DescriptionStyle style = DescriptionStyle.resolveDescriptionStyle(
				choices.stream().map(Choice::description).toList());

		boolean columns = layout.mode() == LayoutMode.COLUMNS;
		int imageSize = columns ? IMAGE_SIZE_COLUMNS : IMAGE_SIZE_ROWS;
		int panelWidth = layout.panelWidth();

		// Width available for description text inside a panel, in screen px.
		int textWidth = columns
				? panelWidth - 2 * (BORDER + PANEL_PADDING)
				: panelWidth - 2 * (BORDER + PANEL_PADDING) - imageSize - 8;
		textWidth = Math.max(textWidth, 8);

		// Wrapping happens in unscaled font space, so divide by the text scale.
		int wrapWidth = Math.max(8, Math.round(textWidth / style.scale()));
		int scaledLineHeight = Math.max(1, Math.round(this.font.lineHeight * style.scale()));

		// Wrap all three first, then give every panel the SAME description block
		// height -- that is what keeps the three panels visually identical.
		List<List<FormattedCharSequence>> wrapped = new ArrayList<>();
		int usedLines = 1;
		for (Choice choice : choices) {
			List<FormattedCharSequence> lines =
					this.font.split(Component.literal(choice.description()), wrapWidth);
			if (lines.size() > style.maxLines()) {
				lines = lines.subList(0, style.maxLines());
			}
			wrapped.add(lines);
			usedLines = Math.max(usedLines, lines.size());
		}
		int descBlockHeight = usedLines * scaledLineHeight;

		int panelHeight = columns
				? BORDER + PANEL_PADDING + imageSize + 6 + this.font.lineHeight + 4
						+ descBlockHeight + PANEL_PADDING + BORDER
				: BORDER + PANEL_PADDING
						+ Math.max(imageSize, this.font.lineHeight + 3 + descBlockHeight)
						+ PANEL_PADDING + BORDER;

		// Vertically centre header + rule + panels as one block.
		int headerBlock = this.font.lineHeight + 6 + ACCENT_RULE_HEIGHT;
		int panelsBlock = columns ? panelHeight : panelHeight * 3 + ROW_GAP * 2;
		int totalHeight = headerBlock + 14 + panelsBlock;

		int top = Math.max(SCREEN_MARGIN, (this.height - totalHeight) / 2);
		this.headerTextY = top;
		this.accentRuleY = top + this.font.lineHeight + 6;
		int panelsTop = this.accentRuleY + ACCENT_RULE_HEIGHT + 14;

		for (int i = 0; i < choices.size(); i++) {
			int x;
			int y;
			if (columns) {
				int totalWidth = panelWidth * 3 + PANEL_GAP * 2;
				x = (this.width - totalWidth) / 2 + i * (panelWidth + PANEL_GAP);
				y = panelsTop;
			} else {
				x = (this.width - panelWidth) / 2;
				y = panelsTop + i * (panelHeight + ROW_GAP);
			}
			this.addRenderableWidget(new ChoicePanel(
					x, y, panelWidth, panelHeight,
					choices.get(i), wrapped.get(i),
					layout.mode(), style, imageSize, scaledLineHeight));
		}
	}

	record DescriptionStyle(float scale, int maxLines) {
		/**
		 * Decides how descriptions are sized for THIS pick. Isolated on purpose:
		 * swapping the strategy later (ellipsis truncation, scrolling, tooltips)
		 * means rewriting only this method, not any render or layout code.
		 *
		 * <p>Lives on the record (rather than on the screen) so it carries no
		 * Minecraft dependency and can be exercised on a bare JVM.
		 */
		static DescriptionStyle resolveDescriptionStyle(List<String> descriptions) {
			for (String description : descriptions) {
				if (description != null && description.length() > DESCRIPTION_COMPACT_THRESHOLD_CHARS) {
					return new DescriptionStyle(COMPACT_TEXT_SCALE, DESCRIPTION_COMPACT_MAX_LINES);
				}
			}
			return new DescriptionStyle(1.0f, DESCRIPTION_NORMAL_MAX_LINES);
		}
	}

	enum LayoutMode { COLUMNS, ROWS }

	/**
	 * Pure responsive-layout maths -- no Minecraft types, so it can be unit
	 * tested on a bare JVM. Three panels side by side whenever they can each
	 * hold {@link #PANEL_MIN_WIDTH}; otherwise a vertical stack. The stacked
	 * width is clamped to the usable area, so nothing can ever clip off-screen
	 * at any GUI scale -- a hard requirement, because this screen has no
	 * escape hatch ({@link #shouldCloseOnEsc()} is false).
	 */
	record PanelLayout(LayoutMode mode, int panelWidth) {
		static PanelLayout resolve(int screenWidth) {
			int usable = Math.max(0, screenWidth - 2 * SCREEN_MARGIN);
			int columnWidth = (usable - 2 * PANEL_GAP) / 3;
			if (columnWidth >= PANEL_MIN_WIDTH) {
				return new PanelLayout(LayoutMode.COLUMNS, Math.min(columnWidth, PANEL_MAX_WIDTH));
			}
			return new PanelLayout(LayoutMode.ROWS, Math.min(usable, ROW_MAX_WIDTH));
		}
	}

	// ---------------------------------------------------------------------
	// Rendering
	// ---------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		String header = (phase == EffectPhase.GOOD ? "A blessing approaches..." : "A curse approaches...")
				+ "  (Entropy: " + entropy + " / " + entropyCap + ")";
		int headerWidth = this.font.width(header);
		graphics.text(this.font, header, this.width / 2 - headerWidth / 2, headerTextY, HEADER_COLOR, true);

		// Thin accent rule tying the header to phase/intensity without
		// recolouring the header text itself.
		int half = Math.min(ACCENT_RULE_MAX_HALF_WIDTH, Math.max(1, (this.width - 2 * SCREEN_MARGIN) / 2));
		graphics.fill(this.width / 2 - half, accentRuleY,
				this.width / 2 + half, accentRuleY + ACCENT_RULE_HEIGHT, accentColor);
	}

	private void onChoose(String effectId) {
		ClientPlayNetworking.send(new ChoiceMadePayload(effectId));
		this.onClose();
	}

	/**
	 * One clickable choice panel. Extends {@link AbstractWidget} rather than
	 * using {@code Button} so the whole panel area is the hit target and the
	 * beveled visual is fully custom.
	 */
	private final class ChoicePanel extends AbstractWidget {
		private final Choice choice;
		private final List<FormattedCharSequence> descriptionLines;
		private final LayoutMode mode;
		private final DescriptionStyle style;
		private final int imageSize;
		private final int scaledLineHeight;

		ChoicePanel(int x, int y, int width, int height, Choice choice,
					List<FormattedCharSequence> descriptionLines, LayoutMode mode,
					DescriptionStyle style, int imageSize, int scaledLineHeight) {
			super(x, y, width, height, Component.literal(choice.name()));
			this.choice = choice;
			this.descriptionLines = descriptionLines;
			this.mode = mode;
			this.style = style;
			this.imageSize = imageSize;
			this.scaledLineHeight = scaledLineHeight;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
			drawChrome(graphics);
			if (mode == LayoutMode.COLUMNS) {
				drawColumnContent(graphics);
			} else {
				drawRowContent(graphics);
			}
		}

		/** Accent border + neutral fill + inset bevel. Fill stays grey so the
		 *  description keeps contrast at every entropy level. */
		private void drawChrome(GuiGraphicsExtractor graphics) {
			int x0 = getX();
			int y0 = getY();
			int x1 = x0 + getWidth();
			int y1 = y0 + getHeight();

			graphics.fill(x0, y0, x1, y1, accentColor);

			int ix0 = x0 + BORDER;
			int iy0 = y0 + BORDER;
			int ix1 = x1 - BORDER;
			int iy1 = y1 - BORDER;
			graphics.fill(ix0, iy0, ix1, iy1, isHovered() ? PANEL_FILL_HOVER : PANEL_FILL);

			// Beveled, not flat: highlight top-left, shadow bottom-right.
			graphics.fill(ix0, iy0, ix1, iy0 + 1, BEVEL_HIGHLIGHT);
			graphics.fill(ix0, iy0, ix0 + 1, iy1, BEVEL_HIGHLIGHT);
			graphics.fill(ix0, iy1 - 1, ix1, iy1, BEVEL_SHADOW);
			graphics.fill(ix1 - 1, iy0, ix1, iy1, BEVEL_SHADOW);
		}

		private void drawColumnContent(GuiGraphicsExtractor graphics) {
			int centerX = getX() + getWidth() / 2;
			int y = getY() + BORDER + PANEL_PADDING;

			drawEffectImage(graphics, centerX - imageSize / 2, y, imageSize);
			y += imageSize + 6;

			int titleWidth = font.width(choice.name());
			graphics.text(font, choice.name(), centerX - titleWidth / 2, y, accentColor, true);
			y += font.lineHeight + 4;

			for (FormattedCharSequence line : descriptionLines) {
				drawDescriptionLine(graphics, line, centerX, y, true);
				y += scaledLineHeight;
			}
		}

		private void drawRowContent(GuiGraphicsExtractor graphics) {
			int imageX = getX() + BORDER + PANEL_PADDING;
			int imageY = getY() + (getHeight() - imageSize) / 2;
			drawEffectImage(graphics, imageX, imageY, imageSize);

			int textX = imageX + imageSize + 8;
			int contentHeight = font.lineHeight + 3 + descriptionLines.size() * scaledLineHeight;
			int y = getY() + (getHeight() - contentHeight) / 2;

			graphics.text(font, choice.name(), textX, y, accentColor, true);
			y += font.lineHeight + 3;

			for (FormattedCharSequence line : descriptionLines) {
				drawDescriptionLine(graphics, line, textX, y, false);
				y += scaledLineHeight;
			}
		}

		private void drawDescriptionLine(GuiGraphicsExtractor graphics, FormattedCharSequence line,
										  int anchorX, int y, boolean centered) {
			if (style.scale() == 1.0f) {
				int w = font.width(line);
				graphics.text(font, line, centered ? anchorX - w / 2 : anchorX, y, DESCRIPTION_COLOR, true);
				return;
			}
			// Minecraft has one font size, so "smaller text" is a matrix scale.
			var pose = graphics.pose();
			pose.pushMatrix();
			pose.translate((float) anchorX, (float) y);
			pose.scale(style.scale(), style.scale());
			int w = font.width(line);
			graphics.text(font, line, centered ? -w / 2 : 0, 0, DESCRIPTION_COLOR, true);
			pose.popMatrix();
		}

		/**
		 * Single swap-in point for real per-effect art. When
		 * {@code EffectDefinition} gains an optional texture {@code Identifier},
		 * blit it here and return early -- no layout code changes, because the
		 * slot is already reserved at {@code imageSize} square.
		 */
		private void drawEffectImage(GuiGraphicsExtractor graphics, int x, int y, int size) {
			drawImagePlaceholder(graphics, x, y, size);
		}

		private void drawImagePlaceholder(GuiGraphicsExtractor graphics, int x, int y, int size) {
			graphics.fill(x, y, x + size, y + size, IMAGE_SLOT_FILL);
			// Recessed look: dark inset on the top-left, opposite of the panel bevel.
			graphics.fill(x, y, x + size, y + 1, IMAGE_SLOT_INSET);
			graphics.fill(x, y, x + 1, y + size, IMAGE_SLOT_INSET);
			drawDashedBorder(graphics, x, y, size);
		}

		private void drawDashedBorder(GuiGraphicsExtractor graphics, int x, int y, int size) {
			final int dash = 3;
			final int gap = 3;
			for (int i = 0; i < size; i += dash + gap) {
				int len = Math.min(dash, size - i);
				graphics.fill(x + i, y, x + i + len, y + 1, IMAGE_SLOT_DASH);
				graphics.fill(x + i, y + size - 1, x + i + len, y + size, IMAGE_SLOT_DASH);
				graphics.fill(x, y + i, x + 1, y + i + len, IMAGE_SLOT_DASH);
				graphics.fill(x + size - 1, y + i, x + size, y + i + len, IMAGE_SLOT_DASH);
			}
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			// Reached for a click anywhere in the panel bounds: AbstractWidget's
			// mouseClicked hit-tests against the full widget rect before calling us.
			onChoose(choice.id());
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	// ---------------------------------------------------------------------
	// Entropy-driven accent colour
	// ---------------------------------------------------------------------

	/**
	 * Maps (entropy, cap, phase) to the accent colour shared by all three
	 * panels in a pick -- it signals overall run state, not per-choice
	 * difference. Deliberately free of Minecraft imports so it can be verified
	 * on a bare JVM.
	 *
	 * <p>Interpolation is in HSL, not RGB: RGB-blending green->blue or
	 * red->purple passes through muddy desaturated grey partway.
	 */
	static final class EntropyPalette {
		/** Boundary between the lightness-only ramp and the endgame hue shift. */
		static final float SEGMENT_SPLIT_T = 0.8f;

		// (hue, saturation%, lightness%) keyframes -- expected to be tuned in-game.
		static final float[] GOOD_START = {130f, 55f, 58f}; // light green
		static final float[] GOOD_MID   = {140f, 55f, 26f}; // dark green
		static final float[] GOOD_END   = {222f, 55f, 26f}; // dark blue

		static final float[] BAD_START = {0f,   65f, 60f};  // light red
		static final float[] BAD_MID   = {355f, 55f, 30f};  // dark red
		static final float[] BAD_END   = {280f, 45f, 32f};  // dark purple

		private EntropyPalette() {}

		static int accent(boolean blessing, int entropy, int entropyCap) {
			float[] hsl = accentHsl(blessing, entropy, entropyCap);
			return hslToArgb(hsl[0], hsl[1], hsl[2]);
		}

		static float[] accentHsl(boolean blessing, int entropy, int entropyCap) {
			float t = entropyCap <= 0 ? 0f : (float) entropy / (float) entropyCap;
			t = Math.max(0f, Math.min(1f, t));

			float[] from;
			float[] to;
			float f;
			if (t <= SEGMENT_SPLIT_T) {
				from = blessing ? GOOD_START : BAD_START;
				to = blessing ? GOOD_MID : BAD_MID;
				f = t / SEGMENT_SPLIT_T;
			} else {
				from = blessing ? GOOD_MID : BAD_MID;
				to = blessing ? GOOD_END : BAD_END;
				f = (t - SEGMENT_SPLIT_T) / (1f - SEGMENT_SPLIT_T);
			}
			return new float[] {
					lerpHue(from[0], to[0], f),
					lerp(from[1], to[1], f),
					lerp(from[2], to[2], f)
			};
		}

		/**
		 * Shortest-arc hue interpolation. This is NOT a naive lerp on purpose:
		 * lerping 0 -> 355 linearly travels 355 degrees the long way round and
		 * cycles the curse ramp through the entire rainbow. Taking the signed
		 * arc keeps it a 5-degree step. Do not "simplify" this.
		 */
		static float lerpHue(float h1, float h2, float f) {
			float diff = ((h2 - h1 + 540f) % 360f) - 180f;
			return (h1 + diff * f + 360f) % 360f;
		}

		static float lerp(float a, float b, float f) {
			return a + (b - a) * f;
		}

		static int hslToArgb(float h, float s, float l) {
			float sN = s / 100f;
			float lN = l / 100f;
			float c = (1f - Math.abs(2f * lN - 1f)) * sN;
			float hp = (((h % 360f) + 360f) % 360f) / 60f;
			float x = c * (1f - Math.abs((hp % 2f) - 1f));

			float r1;
			float g1;
			float b1;
			if (hp < 1f)      { r1 = c;  g1 = x;  b1 = 0f; }
			else if (hp < 2f) { r1 = x;  g1 = c;  b1 = 0f; }
			else if (hp < 3f) { r1 = 0f; g1 = c;  b1 = x;  }
			else if (hp < 4f) { r1 = 0f; g1 = x;  b1 = c;  }
			else if (hp < 5f) { r1 = x;  g1 = 0f; b1 = c;  }
			else              { r1 = c;  g1 = 0f; b1 = x;  }

			float m = lN - c / 2f;
			int r = clamp255(Math.round((r1 + m) * 255f));
			int g = clamp255(Math.round((g1 + m) * 255f));
			int b = clamp255(Math.round((b1 + m) * 255f));
			return 0xFF000000 | (r << 16) | (g << 8) | b;
		}

		private static int clamp255(int v) {
			return Math.max(0, Math.min(255, v));
		}
	}

	// ---------------------------------------------------------------------

	@Override
	public boolean shouldCloseOnEsc() {
		return false; // picking is mandatory -- no dodging the choice
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}
}
