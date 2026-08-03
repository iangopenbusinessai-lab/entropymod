package com.entropymod.entropy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Randomized Movement Controls' permutation, as a 4-character string.
 *
 * <p><b>Deliberately free of Minecraft imports</b>, the same discipline
 * {@code AcquiredEffects}, {@code CropSchedule} and {@code TramplePath} follow.
 * A wrong permutation is invisible from the outside -- it just looks like the
 * scramble it was supposed to be -- so the rules need to be harness-drivable
 * against the real shipped class.
 *
 * <h2>Representation</h2>
 *
 * <p>Four characters over {@code F B L R}, one per <em>input</em> direction in
 * that fixed order. <b>{@code charAt(i)} is the direction the player actually
 * moves when they press the key for {@link #ORDER}{@code .charAt(i)}.</b> So
 * {@code "FBLR"} is the identity, and {@code "LFRB"} means "W walks left, S
 * walks forward, A walks right, D walks back".
 *
 * <p>A string rather than an int array on purpose: it goes into
 * {@code EntropyManager}'s codec as one {@code Codec.STRING} field and onto the
 * wire as one {@code STRING_UTF8}, and it is legible in a log line, which for a
 * bug of the form "the scramble changed when it shouldn't have" is most of the
 * diagnosis.
 *
 * <h2>Why it is persisted rather than re-rolled</h2>
 *
 * <p>The effect is permanent, so the permutation is part of the run's state and
 * must be identical for the rest of it -- a scramble that re-rolled on every
 * respawn would be a different effect (and an unlearnable one). It lives in
 * {@code EntropyManager}'s existing codec beside {@code rerollUsed}, which is
 * the pattern CLAUDE.md records for state-bearing effects: one store, not a
 * parallel one.
 */
public final class MovementScramble {

	/** The canonical input order. Index 0 is forward, 1 back, 2 left, 3 right. */
	public static final String ORDER = "FBLR";

	/** The identity permutation -- vanilla controls. Also the "not yet assigned" value's meaning. */
	public static final String IDENTITY = ORDER;

	/** Length of every valid scramble. */
	public static final int LENGTH = 4;

	/**
	 * A uniformly random permutation that is <b>not</b> the identity.
	 *
	 * <p>Excluding the identity matters: 1 run in 24 would otherwise acquire a
	 * curse that does nothing at all, which is indistinguishable from the effect
	 * being broken -- exactly the "real, but below the perceptual threshold"
	 * failure mode CLAUDE.md records twice already. 23 of the 24 permutations
	 * remain reachable.
	 */
	public static String random(Random random) {
		List<Character> chars = new ArrayList<>(LENGTH);
		for (int i = 0; i < LENGTH; i++) {
			chars.add(ORDER.charAt(i));
		}
		String candidate;
		do {
			java.util.Collections.shuffle(chars, random);
			StringBuilder sb = new StringBuilder(LENGTH);
			for (char c : chars) {
				sb.append(c);
			}
			candidate = sb.toString();
		} while (candidate.equals(IDENTITY));
		return candidate;
	}

	/**
	 * Whether a string is a legal scramble: exactly {@link #LENGTH} characters,
	 * each of {@code F B L R}, none repeated.
	 *
	 * <p>Checked on both sides of the wire and on load. A malformed value must
	 * degrade to vanilla controls rather than throw inside input handling, which
	 * runs every client tick.
	 */
	public static boolean isValid(String scramble) {
		if (scramble == null || scramble.length() != LENGTH) {
			return false;
		}
		boolean[] seen = new boolean[LENGTH];
		for (int i = 0; i < LENGTH; i++) {
			int index = ORDER.indexOf(scramble.charAt(i));
			if (index < 0 || seen[index]) {
				return false;
			}
			seen[index] = true;
		}
		return true;
	}

	/**
	 * Applies the scramble to one tick's raw direction presses.
	 *
	 * @param pressed the raw presses in {@link #ORDER}, i.e. {forward, back, left, right}
	 * @return the effective presses in the same order
	 *
	 * <p>An invalid scramble returns the input unchanged -- vanilla controls --
	 * rather than throwing. See {@link #isValid}.
	 */
	public static boolean[] apply(String scramble, boolean[] pressed) {
		if (!isValid(scramble) || pressed == null || pressed.length != LENGTH) {
			return pressed;
		}
		boolean[] out = new boolean[LENGTH];
		for (int i = 0; i < LENGTH; i++) {
			if (pressed[i]) {
				// charAt(i) is where input direction i actually sends you.
				out[ORDER.indexOf(scramble.charAt(i))] = true;
			}
		}
		return out;
	}

	/**
	 * Vanilla's own impulse maths, reproduced from
	 * {@code KeyboardInput.calculateImpulse} -- verified in bytecode as
	 * "equal ? 0 : (positive ? 1 : -1)".
	 *
	 * <p>Needed because {@code moveVector} is derived from the presses and has to
	 * be recomputed after permuting them; vanilla's own method is {@code private
	 * static} and not callable.
	 */
	public static float impulse(boolean positive, boolean negative) {
		if (positive == negative) {
			return 0.0f;
		}
		return positive ? 1.0f : -1.0f;
	}

	private MovementScramble() {}
}
