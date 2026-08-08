package com.entropymod.entropy.companion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which entities each player's companion effects have already spawned.
 *
 * <p><b>This is the answer to a correctness question this project has not had
 * before: what does idempotency mean for an effect that spawns ENTITIES?</b>
 *
 * <p>Every effect until now was either an attribute or a hook, and for both,
 * {@code apply()} rebuilds <em>derived</em> state from {@code AcquiredEffects} --
 * which is why "apply it ten times, assert the value did not move" is the right
 * test and why {@code addOrUpdateTransientModifier} makes it free. A companion
 * effect is the opposite: the wolves are <b>world state that already exists</b>.
 * Re-running the spawn would not be a no-op, it would be fifteen more wolves, and
 * {@code apply} runs again on every respawn, rejoin and dimension change.
 *
 * <h2>The guarantee is STRUCTURAL, not a lookup</h2>
 *
 * <p>The obvious design is to record each spawned entity's UUID, resolve them on
 * re-application, and top up the shortfall. <b>That is a trap</b>, and it is worth
 * writing down because it looks careful: {@code ServerLevel.getEntity(UUID)} only
 * finds entities in <em>loaded</em> chunks. A player who logs in a thousand blocks
 * from their pack, or steps through a Nether portal, would resolve zero live
 * companions and be handed a fresh set -- and then another on the next relog. The
 * failure mode of a top-up design is unbounded duplication, triggered by exactly
 * the events {@code apply} is called on.
 *
 * <p>So companions are spawned <b>only on {@code EffectContext.isFreshPick()}</b>,
 * the same one-shot gate Slashed Pockets' one-time drop uses. Re-application never
 * spawns anything at all, which makes "no duplicates on respawn" true by
 * construction rather than true if a lookup happens to succeed. The roster is not
 * what prevents duplication; it exists so the run knows what it created.
 *
 * <p><b>The honest cost of that choice:</b> a companion that dies stays dead. For
 * Loyal Pack that is correct -- they are real wolves and mortal. For the three
 * invulnerable effects it cannot arise. Nothing re-summons, ever.
 *
 * <h2>Shape</h2>
 *
 * <p>Keyed by {@code playerUuid + "/" + effectId} so one player can hold several
 * companion effects and so a second player's pack is never confused with the
 * first's. Minecraft-import-free, like {@code AcquiredEffects},
 * {@code KeybindSnapshot} and {@code SpawnSchedule} -- the counting and key rules
 * are exactly the sort of thing that is invisible in play when wrong, so they have
 * to be drivable by the harness.
 */
public final class CompanionRoster {

	private final Map<String, List<String>> byOwnerAndEffect = new LinkedHashMap<>();

	public CompanionRoster() {}

	public CompanionRoster(Map<String, List<String>> initial) {
		initial.forEach((key, ids) -> byOwnerAndEffect.put(key, new ArrayList<>(ids)));
	}

	/** The persisted form. Insertion-ordered so a save round-trip is stable. */
	public Map<String, List<String>> asMap() {
		Map<String, List<String>> copy = new LinkedHashMap<>();
		byOwnerAndEffect.forEach((key, ids) -> copy.put(key, List.copyOf(ids)));
		return copy;
	}

	/**
	 * The composite key. Public and separately testable because getting it wrong
	 * -- keying on the effect alone, say -- would silently share one player's pack
	 * with another's on a shared world.
	 */
	public static String key(String ownerUuid, String effectId) {
		return ownerUuid + "/" + effectId;
	}

	/** Whether this player has already had this effect's companions spawned. */
	public boolean hasSpawned(String ownerUuid, String effectId) {
		return !uuidsFor(ownerUuid, effectId).isEmpty();
	}

	/** The entity UUIDs recorded for this player and effect. Never null. */
	public List<String> uuidsFor(String ownerUuid, String effectId) {
		return List.copyOf(byOwnerAndEffect.getOrDefault(key(ownerUuid, effectId), List.of()));
	}

	/** How many companions this player and effect have on record. */
	public int countFor(String ownerUuid, String effectId) {
		return byOwnerAndEffect.getOrDefault(key(ownerUuid, effectId), List.of()).size();
	}

	/** Records newly spawned entities. Appends -- it does not replace. */
	public void record(String ownerUuid, String effectId, List<String> entityUuids) {
		byOwnerAndEffect
				.computeIfAbsent(key(ownerUuid, effectId), unused -> new ArrayList<>())
				.addAll(entityUuids);
	}

	/**
	 * Drops one entity from the record -- for a companion that has genuinely gone.
	 *
	 * <p>Note this does <b>not</b> make it eligible to be re-spawned: nothing reads
	 * the roster to decide whether to spawn, only {@code isFreshPick()} does. It
	 * keeps the record honest so the follow/defend service is not chasing ghosts.
	 */
	public void forget(String ownerUuid, String effectId, String entityUuid) {
		List<String> ids = byOwnerAndEffect.get(key(ownerUuid, effectId));
		if (ids != null && ids.remove(entityUuid) && ids.isEmpty()) {
			byOwnerAndEffect.remove(key(ownerUuid, effectId));
		}
	}

	/** Every owner/effect key currently holding companions. */
	public java.util.Set<String> keys() {
		return java.util.Set.copyOf(byOwnerAndEffect.keySet());
	}

	public boolean isEmpty() {
		return byOwnerAndEffect.isEmpty();
	}

	public int totalRecorded() {
		return byOwnerAndEffect.values().stream().mapToInt(List::size).sum();
	}
}
