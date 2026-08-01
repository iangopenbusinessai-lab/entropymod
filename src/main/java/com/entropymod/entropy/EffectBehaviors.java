package com.entropymod.entropy;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.behavior.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * id -> {@link EffectBehavior} lookup. Deliberately the same shape as
 * {@link EffectRegistry}: one {@code register(...)} line per effect and nothing
 * else, so adding effect #47 is a mechanical two-line change (one here, one
 * there) plus one new file.
 *
 * <p>These ids MUST match {@link EffectRegistry}'s exactly. A typo here does not
 * fail to compile -- it silently yields a no-op effect. {@link #validate()} runs
 * at mod init and logs both directions of mismatch precisely because the
 * compiler cannot catch this class of bug.
 */
public final class EffectBehaviors {

	private static final Map<String, EffectBehavior> BY_ID = new HashMap<>();

	static {
		// --- TIER 1 GOOD ---
		register("sure_footing", new SureFootingBehavior());
		register("iron_stomach", new IronStomachBehavior());
		register("featherlight", new FeatherlightBehavior());
		register("prospectors_eye", new ProspectorsEyeBehavior());
		register("field_repair", new FieldRepairBehavior());
		register("night_owl", new NightOwlBehavior());

		// --- TIER 1 BAD ---
		register("butterfingers", new ButterfingersBehavior());
		register("heavy_boots", new HeavyBootsBehavior());
		register("growling_stomach", new GrowlingStomachBehavior());
		register("foggy_head", new FoggyHeadBehavior());
		register("dull_blade", new DullBladeBehavior());

		// TODO: Tiers 2-4 + odd/signature effects, same one-line-per-effect pattern.
	}

	/**
	 * Used when an id has no registered behavior. Logs loudly instead of throwing:
	 * a missing behavior should not be able to break the core loop or strand the
	 * player on an open GUI, and the mismatch is already reported by
	 * {@link #validate()} at startup.
	 */
	private static final EffectBehavior MISSING = new EffectBehavior() {
		@Override
		public void apply(EffectContext ctx) {
			EntropyMod.LOGGER.error("No EffectBehavior registered for id '{}' -- apply() did nothing. "
					+ "Add it to EffectBehaviors.", ctx.effect().id());
		}

		@Override
		public void remove(EffectContext ctx) {
			EntropyMod.LOGGER.error("No EffectBehavior registered for id '{}' -- remove() did nothing.",
					ctx.effect().id());
		}
	};

	private static void register(String id, EffectBehavior behavior) {
		EffectBehavior previous = BY_ID.put(id, behavior);
		if (previous != null) {
			EntropyMod.LOGGER.error("Duplicate EffectBehavior registration for id '{}' -- {} replaced {}.",
					id, behavior.getClass().getSimpleName(), previous.getClass().getSimpleName());
		}
	}

	/** Never null -- returns a logging no-op for unregistered ids. */
	public static EffectBehavior get(String id) {
		return BY_ID.getOrDefault(id, MISSING);
	}

	/** Effect ids that exist in {@link EffectRegistry} but have no behavior here. */
	public static List<String> definitionsWithoutBehavior() {
		List<String> missing = new ArrayList<>();
		for (EffectDefinition def : EffectRegistry.all()) {
			if (!BY_ID.containsKey(def.id())) {
				missing.add(def.id());
			}
		}
		return missing;
	}

	/** Behaviors registered here whose id matches no {@link EffectRegistry} entry (almost always a typo). */
	public static List<String> behaviorsWithoutDefinition() {
		List<String> orphans = new ArrayList<>();
		for (String id : BY_ID.keySet()) {
			if (EffectRegistry.byId(id) == null) {
				orphans.add(id);
			}
		}
		return orphans;
	}

	/** Called once at mod init. Logs mismatches; never throws. */
	public static void validate() {
		List<String> missing = definitionsWithoutBehavior();
		List<String> orphans = behaviorsWithoutDefinition();
		if (!missing.isEmpty()) {
			EntropyMod.LOGGER.error("{} effect(s) have no EffectBehavior and will do nothing when picked: {}",
					missing.size(), missing);
		}
		if (!orphans.isEmpty()) {
			EntropyMod.LOGGER.error("{} EffectBehavior(s) registered under an id no EffectDefinition uses "
					+ "(check for a typo): {}", orphans.size(), orphans);
		}
		if (missing.isEmpty() && orphans.isEmpty()) {
			EntropyMod.LOGGER.info("EffectBehaviors: {} effect(s) wired, no mismatches.", BY_ID.size());
		}
	}

	private EffectBehaviors() {}
}
