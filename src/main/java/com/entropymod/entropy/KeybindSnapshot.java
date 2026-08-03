package com.entropymod.entropy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * The four movement keys as they were bound at the moment the run started.
 *
 * <p>Each field is a vanilla key <em>name</em> -- {@code "key.keyboard.w"},
 * {@code "key.mouse.left"} -- which is exactly the string
 * {@code KeyMapping.saveString()} produces and {@code options.txt} stores.
 * Storing the name rather than a numeric code is what makes this stable across
 * restarts and legible in a save file, and it round-trips through
 * {@code InputConstants.getKey(String)} on the client.
 *
 * <p><b>Deliberately free of Minecraft imports</b>, the same discipline
 * {@link MovementScramble}, {@link AcquiredEffects} and {@code TramplePath}
 * follow -- {@code com.mojang.serialization} is DataFixerUpper, not Minecraft.
 * That is what lets the headless harness round-trip the codec and drive the
 * validity rules against the real shipped class rather than a copy.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Randomized Movement Controls permutes <em>directions</em>. Rebinding
 * permutes <em>keys to directions</em>. Composed, the player can cancel the
 * curse exactly by rebinding their movement keys to undo the scramble -- a
 * complete counter, available in the vanilla Controls menu, taking seconds.
 * Anchoring the curse to the keys as they were at Start closes that: the
 * physical key that meant "forward" when the run began is the one the scramble
 * is defined over, for the rest of the run.
 *
 * <p>The field order is {@link MovementScramble#ORDER} -- forward, back, left,
 * right -- and {@link #keys()} returns them in that order so the array handed to
 * {@code MovementScramble.apply} is index-compatible with the scramble string
 * without any re-mapping step.
 */
public record KeybindSnapshot(String forward, String back, String left, String right) {

	/** "No snapshot taken." Distinct from a snapshot of four unbound keys. */
	public static final KeybindSnapshot EMPTY = new KeybindSnapshot("", "", "", "");

	/**
	 * Every field is {@code optionalFieldOf} with an empty default, matching
	 * {@code EntropyManager}'s codec discipline: a save written before this
	 * existed must still load, and it loads as {@link #EMPTY}.
	 */
	public static final Codec<KeybindSnapshot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.optionalFieldOf("forward", "").forGetter(KeybindSnapshot::forward),
			Codec.STRING.optionalFieldOf("back", "").forGetter(KeybindSnapshot::back),
			Codec.STRING.optionalFieldOf("left", "").forGetter(KeybindSnapshot::left),
			Codec.STRING.optionalFieldOf("right", "").forGetter(KeybindSnapshot::right)
	).apply(instance, KeybindSnapshot::new));

	/** The four key names in {@link MovementScramble#ORDER}. */
	public List<String> keys() {
		return List.of(forward, back, left, right);
	}

	/**
	 * Whether this is a real snapshot rather than the absent one.
	 *
	 * <p>Requires all four names to be non-blank. A partially-filled snapshot is
	 * treated as absent rather than half-honoured, because a curse that anchored
	 * two directions and left the other two live would be a third behaviour that
	 * nothing else in the design accounts for.
	 */
	public boolean isPresent() {
		for (String key : keys()) {
			if (key == null || key.isBlank()) {
				return false;
			}
		}
		return true;
	}

	/** Builds from four names in {@link MovementScramble#ORDER}, or {@link #EMPTY} if malformed. */
	public static KeybindSnapshot of(List<String> keys) {
		if (keys == null || keys.size() != MovementScramble.LENGTH) {
			return EMPTY;
		}
		KeybindSnapshot snapshot =
				new KeybindSnapshot(keys.get(0), keys.get(1), keys.get(2), keys.get(3));
		return snapshot.isPresent() ? snapshot : EMPTY;
	}
}
