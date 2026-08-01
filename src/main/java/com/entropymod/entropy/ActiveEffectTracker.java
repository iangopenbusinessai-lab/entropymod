package com.entropymod.entropy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The list of currently-active effects, plus the two rules that read it:
 * expiry (drives {@link EffectBehavior#remove}) and category occupancy (drives
 * anti-stacking).
 *
 * <p>Kept separate from {@link EntropyManager} and free of Minecraft imports so
 * the timing rules can be tested headlessly against the real class. It returns
 * expired effects rather than invoking callbacks itself: firing behaviors needs
 * a server, and keeping that out of here is what makes it testable.
 */
public final class ActiveEffectTracker {

	private final List<ActiveEffect> active = new ArrayList<>();

	/**
	 * Starts tracking a freshly-picked effect.
	 *
	 * @return the tracked entry, or null if the effect is instantaneous
	 *         ({@code durationTicks == 0}) and therefore never tracked
	 */
	public ActiveEffect add(EffectDefinition definition) {
		if (definition.durationTicks() == ActiveEffect.DURATION_INSTANT) {
			return null;
		}
		// Re-picking an effect that is still running refreshes its timer instead of
		// double-tracking it (which would fire remove() twice). This is not the
		// "replace same-category" rule -- it only ever matches the identical id, and
		// is normally unreachable because anti-stacking already excludes the whole
		// category. It exists for the collision-fallback path below.
		for (ActiveEffect existing : active) {
			if (existing.effectId().equals(definition.id())) {
				existing.refresh(definition.durationTicks());
				return existing;
			}
		}
		ActiveEffect tracked = new ActiveEffect(definition);
		active.add(tracked);
		return tracked;
	}

	/**
	 * Advances every countdown by one tick and removes anything that hit zero.
	 * Interval-scoped effects are untouched -- see {@link #expireIntervalScoped()}.
	 *
	 * @return the effects that expired on this tick, in the order they were added
	 */
	public List<ActiveEffect> tickAndCollectExpired() {
		if (active.isEmpty()) {
			return List.of();
		}
		List<ActiveEffect> expired = null;
		for (Iterator<ActiveEffect> it = active.iterator(); it.hasNext(); ) {
			ActiveEffect effect = it.next();
			if (effect.tick()) {
				it.remove();
				if (expired == null) {
					expired = new ArrayList<>(2);
				}
				expired.add(effect);
			}
		}
		return expired == null ? List.of() : expired;
	}

	/**
	 * Ends every {@code durationTicks == -1} effect. Called when the next interval
	 * fires, which is the literal definition of their duration -- so their real
	 * length is whatever the interval happened to be, and it stays correct if the
	 * interval is reconfigured mid-run.
	 *
	 * @return the effects that just ended
	 */
	public List<ActiveEffect> expireIntervalScoped() {
		List<ActiveEffect> expired = null;
		for (Iterator<ActiveEffect> it = active.iterator(); it.hasNext(); ) {
			ActiveEffect effect = it.next();
			if (effect.isUntilNextInterval()) {
				it.remove();
				if (expired == null) {
					expired = new ArrayList<>(2);
				}
				expired.add(effect);
			}
		}
		return expired == null ? List.of() : expired;
	}

	/** Categories that already have an active effect -- excluded from the next roll. */
	public Set<EffectCategory> occupiedCategories() {
		Set<EffectCategory> occupied = EnumSet.noneOf(EffectCategory.class);
		for (ActiveEffect effect : active) {
			occupied.add(effect.category());
		}
		return occupied;
	}

	public List<ActiveEffect> active() {
		return Collections.unmodifiableList(active);
	}

	public boolean isEmpty() {
		return active.isEmpty();
	}

	/** Drops everything without firing removals. For run-reset only, not for expiry. */
	public void clear() {
		active.clear();
	}
}
