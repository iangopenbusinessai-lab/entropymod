package com.entropymod.client;

import com.entropymod.entropy.EffectPhase;

/**
 * Single source of truth for the entropy-driven accent colour.
 *
 * <p>Everything that tints itself by run state -- {@code ChoiceScreen}'s panel
 * borders and titles, its header rule, and the persistent HUD readout -- calls
 * {@link #colorAt} rather than owning any of this maths itself. If the ramp
 * needs retuning, the six keyframes below are the only thing to edit.
 *
 * <p>Interpolation is in HSL, not RGB: RGB-blending green->blue or red->purple
 * passes through muddy desaturated grey partway. The ramp runs in two segments
 * over {@code t = entropy / entropyCap}, split at {@link #SEGMENT_SPLIT_T} --
 * a lightness-only darkening, then a hue rotation to the endgame colour.
 *
 * <p>Deliberately free of Minecraft imports (it only touches {@link EffectPhase},
 * a plain enum) so the ramp can be exercised by a bare-JVM harness against the
 * real shipped code rather than a copy.
 */
public final class EntropyColors {

	/** Boundary between the lightness-only ramp and the endgame hue shift. */
	public static final float SEGMENT_SPLIT_T = 0.8f;

	// (hue, saturation%, lightness%) keyframes -- expected to be tuned in-game.
	static final float[] GOOD_START = {130f, 55f, 58f}; // light green
	static final float[] GOOD_MID   = {140f, 55f, 26f}; // dark green
	static final float[] GOOD_END   = {222f, 55f, 26f}; // dark blue

	static final float[] BAD_START = {0f,   65f, 60f};  // light red
	static final float[] BAD_MID   = {355f, 55f, 30f};  // dark red
	static final float[] BAD_END   = {280f, 45f, 32f};  // dark purple

	/**
	 * Used when no phase is known yet -- e.g. the HUD on a fresh world before
	 * the first pick has ever been sent. Deliberately neutral rather than
	 * defaulting to a phase, which would imply a run state that doesn't exist.
	 */
	public static final int NEUTRAL = 0xFFAAAAAA;

	private EntropyColors() {}

	/** Resolved ARGB accent for this point in the run. */
	public static int colorAt(int entropy, int entropyCap, EffectPhase phase) {
		float[] hsl = hslAt(entropy, entropyCap, phase);
		return hslToArgb(hsl[0], hsl[1], hsl[2]);
	}

	/** Exposed separately so tests can assert on hue travel, not just final RGB. */
	public static float[] hslAt(int entropy, int entropyCap, EffectPhase phase) {
		float t = entropyCap <= 0 ? 0f : (float) entropy / (float) entropyCap;
		t = Math.max(0f, Math.min(1f, t));

		boolean blessing = phase == EffectPhase.GOOD;
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
				hueLerp(from[0], to[0], f),
				lerp(from[1], to[1], f),
				lerp(from[2], to[2], f)
		};
	}

	/**
	 * Shortest-arc hue interpolation. This is NOT a naive lerp on purpose:
	 * lerping 0 -> 355 linearly travels 355 degrees the long way round and
	 * cycles the curse ramp through the entire rainbow. Taking the signed arc
	 * keeps it a 5-degree step. Do not "simplify" this.
	 */
	public static float hueLerp(float h1, float h2, float f) {
		float diff = ((h2 - h1 + 540f) % 360f) - 180f;
		return (h1 + diff * f + 360f) % 360f;
	}

	public static float lerp(float a, float b, float f) {
		return a + (b - a) * f;
	}

	public static int hslToArgb(float h, float s, float l) {
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
