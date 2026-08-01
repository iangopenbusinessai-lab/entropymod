package com.entropymod.entropy;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every effect this run has accumulated, in pick order. Since all effects are
 * permanent, this is simultaneously:
 *
 * <ul>
 *   <li>the <b>active</b> set — what is currently in force on the player, and
 *       therefore what has to be re-applied after a respawn or a rejoin;</li>
 *   <li>the <b>already-picked</b> set — what the no-repeat rule excludes from
 *       future rolls;</li>
 *   <li>the <b>category occupancy</b> source for anti-stacking.</li>
 * </ul>
 *
 * Those were three separate concerns under the old temporary-effect model and
 * collapsed into one when effects became permanent. Keeping them as one set is
 * the point — they cannot drift apart.
 *
 * <p>This replaced {@code ActiveEffectTracker}. The countdown machinery it used
 * to carry ({@code tickAndCollectExpired}, {@code expireIntervalScoped}, the
 * whole {@code ActiveEffect} class with its {@code remainingTicks}) is gone
 * rather than left unreachable — nothing expires any more.
 *
 * <p>Stores ids, not {@link EffectDefinition}s, so it round-trips through
 * persistence as plain strings. Resolve through {@link EffectRegistry} at the
 * point of use.
 *
 * <p>Deliberately free of Minecraft imports so the rules can be tested by a
 * plain {@code java -cp build/classes/java/main} harness. See CLAUDE.md.
 */
public final class AcquiredEffects {

	/** Insertion-ordered so persistence and the status readout are stable and readable. */
	private final LinkedHashSet<String> ids = new LinkedHashSet<>();

	/**
	 * Records a picked effect.
	 *
	 * @return true if this was newly added; false if it was already present, which
	 *         under the no-repeat rule should only ever happen via the repeat
	 *         fallback (see {@link EffectRegistry#roll})
	 */
	public boolean add(String effectId) {
		return ids.add(effectId);
	}

	public boolean contains(String effectId) {
		return ids.contains(effectId);
	}

	/** Ids in pick order. Unmodifiable. */
	public Set<String> ids() {
		return Collections.unmodifiableSet(ids);
	}

	/**
	 * Resolved definitions, skipping ids the registry no longer knows. Only
	 * reachable if an effect is deleted from the registry between sessions of a
	 * persisted run; dropping it silently here beats refusing to load the save.
	 */
	public List<EffectDefinition> definitions() {
		return ids.stream()
				.map(EffectRegistry::byId)
				.filter(java.util.Objects::nonNull)
				.toList();
	}

	/** Ids present here that the registry no longer defines. Reported at load time. */
	public List<String> unknownIds() {
		return ids.stream().filter(id -> EffectRegistry.byId(id) == null).toList();
	}

	/** Categories that already have an effect -- excluded from the next roll by anti-stacking. */
	public Set<EffectCategory> occupiedCategories() {
		Set<EffectCategory> occupied = EnumSet.noneOf(EffectCategory.class);
		for (EffectDefinition definition : definitions()) {
			occupied.add(definition.category());
		}
		return occupied;
	}

	public int size() {
		return ids.size();
	}

	public boolean isEmpty() {
		return ids.isEmpty();
	}

	public void clear() {
		ids.clear();
	}

	@Override
	public String toString() {
		return ids.toString();
	}
}
