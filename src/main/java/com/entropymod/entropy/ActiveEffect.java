package com.entropymod.entropy;

/**
 * One currently-running effect. Pure bookkeeping -- knows nothing about what the
 * effect does, only when it should stop and which category it is occupying.
 *
 * <p>Deliberately free of any Minecraft import, so the expiry and anti-stacking
 * rules can be exercised by a plain {@code java -cp build/classes/java/main}
 * harness against the real shipped class rather than a copy. Same discipline as
 * {@code EntropyPalette} on the client side (see CLAUDE.md).
 */
public final class ActiveEffect {

	/** {@code durationTicks == 0}: apply once, never tracked, {@code remove} never called. */
	public static final int DURATION_INSTANT = 0;

	/** {@code durationTicks == -1}: runs until the next interval fires, however long that turns out to be. */
	public static final int DURATION_UNTIL_NEXT_INTERVAL = -1;

	private final String effectId;
	private final EffectCategory category;

	/**
	 * True for {@link #DURATION_UNTIL_NEXT_INTERVAL} effects. These are expired by
	 * an <em>event</em> (the next pick being triggered), not by a countdown, which
	 * is the whole point: converting "until next interval" into a fixed tick count
	 * at apply time would silently go wrong the moment the interval length changed.
	 */
	private final boolean untilNextInterval;

	/** Only meaningful when {@link #untilNextInterval} is false. */
	private int remainingTicks;

	ActiveEffect(EffectDefinition definition) {
		if (definition.durationTicks() == DURATION_INSTANT) {
			throw new IllegalArgumentException(
					"Duration-0 effect '" + definition.id() + "' must never be tracked as active");
		}
		this.effectId = definition.id();
		this.category = definition.category();
		this.untilNextInterval = definition.durationTicks() == DURATION_UNTIL_NEXT_INTERVAL;
		this.remainingTicks = this.untilNextInterval ? -1 : definition.durationTicks();
	}

	public String effectId() {
		return effectId;
	}

	public EffectCategory category() {
		return category;
	}

	public boolean isUntilNextInterval() {
		return untilNextInterval;
	}

	/** -1 for interval-scoped effects, which have no countdown. */
	public int remainingTicks() {
		return remainingTicks;
	}

	/** Counts down one tick. Returns true if the effect has just expired. No-op for interval-scoped effects. */
	boolean tick() {
		if (untilNextInterval) {
			return false;
		}
		remainingTicks--;
		return remainingTicks <= 0;
	}

	/** Restarts the countdown -- used when the same effect is picked again while still active. */
	void refresh(int durationTicks) {
		if (!untilNextInterval) {
			this.remainingTicks = durationTicks;
		}
	}

	@Override
	public String toString() {
		return untilNextInterval
				? effectId + "[" + category + ", until next interval]"
				: effectId + "[" + category + ", " + remainingTicks + "t left]";
	}
}
