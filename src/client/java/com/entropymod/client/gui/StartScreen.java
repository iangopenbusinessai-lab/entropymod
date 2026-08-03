package com.entropymod.client.gui;

import com.entropymod.client.KeybindCapture;
import com.entropymod.network.KeybindSnapshotPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The modal gate at the front of a run. Shown while the server reports
 * {@code NOT_STARTED}; clicking Start is what sets the entropy loop running.
 *
 * <p>Same interaction contract as {@link ChoiceScreen}: no escape key, no close
 * button, world paused. <b>That makes the responsive layout a correctness
 * requirement rather than polish</b> -- a button that renders off-screen at some
 * GUI scale leaves the player with no way out of the world at all. Everything
 * here is sized from {@code this.width}/{@code this.height} and centred as one
 * block; nothing uses a fixed coordinate.
 *
 * <h2>Clicking Start does two things, and only one of them is local</h2>
 *
 * <p>The state transition is server-authoritative; the keybind snapshot can only
 * be read on the client. Rather than sequencing them, <b>the snapshot is sent as
 * the start message</b> -- one {@link KeybindSnapshotPayload} with
 * {@code startRun = true}. There is no ordering to get wrong and no path that
 * starts a run without capturing keybinds, because they are the same packet.
 *
 * <p><b>This screen does not close itself.</b> It waits for the server's
 * {@code RunSyncPayload} to report {@code IN_PROGRESS}, which
 * {@code EntropyModClient} turns into the close. A screen that closed on its own
 * click would put the player into a world whose loop had refused to start, with
 * no way to ask again.
 *
 * <h2>Deliberately button-only</h2>
 *
 * <p>The recorded architecture puts the interval-length and entropy-cap settings
 * on this panel. They are <b>not built</b>: sliders plus a client-to-server
 * settings channel plus validation is a materially larger piece of work than the
 * gate itself, and the gate is what the keybind snapshot needs. The settings
 * remain deferred scope -- see CLAUDE.md. When they land, they belong in the same
 * centred block so the no-clipping rule keeps holding.
 */
public class StartScreen extends Screen {

	private static final int SCREEN_MARGIN = 12;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_MIN_WIDTH = 100;
	private static final int BUTTON_MAX_WIDTH = 220;
	private static final int TEXT_MAX_WIDTH = 300;
	private static final int GAP = 10;

	private static final int TITLE_COLOR = 0xFFFFFFFF;
	private static final int BODY_COLOR = 0xFFC8C8C8;
	private static final int RULE_COLOR = 0xFF9A9A9A;
	private static final int RULE_HEIGHT = 2;

	private static final String BODY =
			"Every few minutes you will be offered three Blessings, then three Curses, "
					+ "forever. Each pick raises Entropy by one. Beat the Ender Dragon before "
					+ "Entropy reaches the cap. Nothing happens until you start.";

	private Button startButton;
	private List<FormattedCharSequence> bodyLines = List.of();
	private int titleY;
	private int ruleY;
	private int bodyY;

	public StartScreen() {
		super(Component.literal("Entropy"));
	}

	@Override
	protected void init() {
		int textWidth = Math.min(TEXT_MAX_WIDTH, Math.max(40, this.width - 2 * SCREEN_MARGIN));
		this.bodyLines = new ArrayList<>(this.font.split(Component.literal(BODY), textWidth));

		int buttonWidth = Math.min(BUTTON_MAX_WIDTH,
				Math.max(BUTTON_MIN_WIDTH, this.width - 2 * SCREEN_MARGIN));

		// Title + rule + body + button measured and centred as ONE block, so the
		// button cannot be pushed past the bottom edge on a short window.
		int blockHeight = this.font.lineHeight
				+ GAP + RULE_HEIGHT
				+ GAP + this.bodyLines.size() * this.font.lineHeight
				+ GAP + BUTTON_HEIGHT;

		int top = Math.max(SCREEN_MARGIN, (this.height - blockHeight) / 2);
		this.titleY = top;
		this.ruleY = top + this.font.lineHeight + GAP;
		this.bodyY = this.ruleY + RULE_HEIGHT + GAP;
		int buttonY = this.bodyY + this.bodyLines.size() * this.font.lineHeight + GAP;

		// Clamp so the button stays fully on screen even if the text block alone
		// overflows a very short window -- the text may be clipped, the only way
		// out never is.
		buttonY = Math.min(buttonY, Math.max(0, this.height - SCREEN_MARGIN - BUTTON_HEIGHT));

		this.startButton = Button.builder(Component.literal("Start Run"), button -> onStart())
				.bounds(this.width / 2 - buttonWidth / 2, buttonY, buttonWidth, BUTTON_HEIGHT)
				.build();
		this.addRenderableWidget(this.startButton);
	}

	/**
	 * Sends the capture-and-start packet. The button is disabled immediately so a
	 * double-click cannot send two -- the server's {@code startRun} is idempotent
	 * and would reject the second anyway, but there is no reason to make it
	 * arbitrate a race this side can simply not start.
	 */
	private void onStart() {
		if (this.startButton != null) {
			this.startButton.active = false;
		}
		if (!ClientPlayNetworking.canSend(KeybindSnapshotPayload.TYPE)) {
			// Not an Entropy Mod server, or an older one. Don't strand the player
			// behind a modal screen whose button talks to nobody.
			this.onClose();
			return;
		}
		ClientPlayNetworking.send(new KeybindSnapshotPayload(KeybindCapture.capture(), true));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		String title = "Entropy";
		graphics.text(this.font, title, this.width / 2 - this.font.width(title) / 2,
				this.titleY, TITLE_COLOR, true);

		int half = Math.min(150, Math.max(1, (this.width - 2 * SCREEN_MARGIN) / 2));
		graphics.fill(this.width / 2 - half, this.ruleY,
				this.width / 2 + half, this.ruleY + RULE_HEIGHT, RULE_COLOR);

		int y = this.bodyY;
		for (FormattedCharSequence line : this.bodyLines) {
			graphics.text(this.font, line, this.width / 2 - this.font.width(line) / 2, y, BODY_COLOR, true);
			y += this.font.lineHeight;
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false; // the run must be started deliberately -- same contract as ChoiceScreen
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}
}
