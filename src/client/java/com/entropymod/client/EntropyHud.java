package com.entropymod.client;

import com.entropymod.entropy.EffectPhase;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Persistent "Entropy: N/cap" readout in the top-right corner, tinted by the
 * same ramp the choice panels use ({@link EntropyColors}).
 *
 * <p>API NOTE: {@code HudRenderCallback} does NOT exist in this Fabric API
 * version -- the HUD API is {@code HudElementRegistry} + {@link HudElement},
 * and the element hands you the same {@link GuiGraphicsExtractor} the screen
 * pipeline uses. Verified by javap against fabric-rendering-v1, not assumed.
 *
 * <p>The {@code screen == null} guard in {@link #extractRenderState} is
 * REQUIRED, not defensive tidiness. Checked in {@code GameRenderer}'s
 * bytecode: the HUD call ({@code Gui.extractRenderState}) is gated only on a
 * boolean parameter, whereas the screen call is separately gated on
 * {@code minecraft.screen != null}. Nothing stops the HUD drawing underneath
 * an open screen -- so without this guard the readout renders behind
 * ChoiceScreen's background.
 *
 * <p>Client-side cache: the server only talks to the client when a pick opens,
 * so the last {@code OpenChoicePayload} is the only entropy state the client
 * has. See {@link #update} and {@link #noteChoiceSubmitted()}.
 */
public final class EntropyHud implements HudElement {

	/** Logical px inset from the top-right corner. */
	private static final int MARGIN = 6;

	// Cached from the most recent OpenChoicePayload. Client thread only.
	private static boolean hasData = false;
	private static EffectPhase lastPhase = EffectPhase.GOOD;
	private static int lastEntropy = 0;
	private static int lastCap = 0;

	/** Called from the OpenChoicePayload receiver (and /entropypreview). */
	public static void update(EffectPhase phase, int entropy, int entropyCap) {
		lastPhase = phase;
		lastEntropy = entropy;
		lastCap = entropyCap;
		hasData = true;
	}

	/**
	 * Optimistic local increment when the player submits a pick.
	 *
	 * <p>Without this the HUD would sit on a stale value for the entire
	 * interval, because the server sends nothing between picks -- it would
	 * read "4/100" for three minutes after you had actually reached 5. This
	 * mirrors exactly what {@code EntropyManager#onChoiceMade} does for a valid
	 * submission, and the next payload re-syncs it regardless, so a rejected
	 * submission self-corrects rather than drifting.
	 */
	public static void noteChoiceSubmitted() {
		if (hasData) {
			lastEntropy = Math.min(lastEntropy + 1, lastCap);
		}
	}

	/** Forget cached state on disconnect so a new world doesn't inherit it. */
	public static void reset() {
		hasData = false;
		lastEntropy = 0;
		lastCap = 0;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();

		// See class javadoc -- the HUD genuinely does render under open screens.
		if (client.screen != null || client.player == null) {
			return;
		}

		String text = hasData
				? "Entropy: " + lastEntropy + "/" + lastCap
				: "Entropy: --";
		// Neutral until a pick has actually happened: guessing a phase would
		// imply a run state that does not exist yet.
		int color = hasData
				? EntropyColors.colorAt(lastEntropy, lastCap, lastPhase)
				: EntropyColors.NEUTRAL;

		int x = graphics.guiWidth() - client.font.width(text) - MARGIN;
		graphics.text(client.font, text, x, MARGIN, color, true);
	}
}
