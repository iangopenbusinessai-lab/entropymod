package com.entropymod.entropy;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.behavior.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * id -&gt; {@link EffectBehavior} lookup. Deliberately the same shape as
 * {@link EffectRegistry}: one {@code register(...)} line per effect and nothing
 * else, so adding effect #47 is a mechanical two-line change (one here, one
 * there) plus one new file.
 *
 * <p>These ids MUST match {@link EffectRegistry}'s exactly. A typo here does not
 * fail to compile -- it silently yields a no-op effect. {@link #validate()} runs
 * at mod init and logs both directions of mismatch precisely because the
 * compiler cannot catch this class of bug. Each behavior exposes its own
 * {@code ID} constant and it is used below rather than a repeated string
 * literal, which shrinks the window for that typo to the one place the constant
 * is declared.
 */
public final class EffectBehaviors {

	private static final Map<String, EffectBehavior> BY_ID = new HashMap<>();

	static {
		// ---------- TIER 1 GOOD ----------
		register(SureFootingBehavior.ID, new SureFootingBehavior());
		register(ThickHideBehavior.ID, new ThickHideBehavior());
		register(SteadyHandsBehavior.ID, new SteadyHandsBehavior());
		register(LuckyFindBehavior.ID, new LuckyFindBehavior());
		register(SwiftStrikesBehavior.ID, new SwiftStrikesBehavior());
		register(FeatherlightBehavior.ID, new FeatherlightBehavior());
		register(EfficientMinerBehavior.ID, new EfficientMinerBehavior());
		register(IronStomachBehavior.ID, new IronStomachBehavior());
		register(IronSkinBehavior.ID, new IronSkinBehavior());
		register(FastLearnerBehavior.ID, new FastLearnerBehavior());
		register(MoonWalkerBehavior.ID, new MoonWalkerBehavior());
		register(MagneticBootsBehavior.ID, new MagneticBootsBehavior());
		register(FrostWalkerInnateBehavior.ID, new FrostWalkerInnateBehavior());
		register(IronWillBehavior.ID, new IronWillBehavior());
		register(BeastWhispererBehavior.ID, new BeastWhispererBehavior());
		register(GreenThumbBehavior.ID, new GreenThumbBehavior());
		register(SecondGuessBehavior.ID, new SecondGuessBehavior());

		// ---------- TIER 1 BAD ----------
		register(HeavyBootsBehavior.ID, new HeavyBootsBehavior());
		register(BrittleBonesBehavior.ID, new BrittleBonesBehavior());
		register(WeakGripBehavior.ID, new WeakGripBehavior());
		register(UnluckyBehavior.ID, new UnluckyBehavior());
		register(SluggishStrikesBehavior.ID, new SluggishStrikesBehavior());
		register(GlassJawBehavior.ID, new GlassJawBehavior());
		register(DullBladeBehavior.ID, new DullBladeBehavior());
		register(GrowlingStomachBehavior.ID, new GrowlingStomachBehavior());
		register(FragileBehavior.ID, new FragileBehavior());
		register(SlowLearnerBehavior.ID, new SlowLearnerBehavior());
		register(LeadenLegsBehavior.ID, new LeadenLegsBehavior());
		register(StoneFeetBehavior.ID, new StoneFeetBehavior());
		register(ExposedBehavior.ID, new ExposedBehavior());
		register(BlightTouchedBehavior.ID, new BlightTouchedBehavior());
		register(LeakyPocketsBehavior.ID, new LeakyPocketsBehavior());
		register(ClumsyDiggerBehavior.ID, new ClumsyDiggerBehavior());
		register(BadReputationBehavior.ID, new BadReputationBehavior());

		// ---------- TIER 2 GOOD ----------
		register(EmbraceTheMoonBehavior.ID, new EmbraceTheMoonBehavior());
		register(CreativeFlightBehavior.ID, new CreativeFlightBehavior());
		register(BehemothGauntletsBehavior.ID, new BehemothGauntletsBehavior());
		register(CrouchInvincibilityBehavior.ID, new CrouchInvincibilityBehavior());
		register(PhoenixChamberedHeartBehavior.ID, new PhoenixChamberedHeartBehavior());
		register(GlassCannonPactBehavior.ID, new GlassCannonPactBehavior());

		// ---------- TIER 2 BAD ----------
		register(GiantSizeBehavior.ID, new GiantSizeBehavior());
		register(SlipperyGripBehavior.ID, new SlipperyGripBehavior());
		register(SlashedPocketsBehavior.ID, new SlashedPocketsBehavior());
		register(FlamboyantBehavior.ID, new FlamboyantBehavior());
		register(RandomizedControlsBehavior.ID, new RandomizedControlsBehavior());
		register(UpsideDownCameraBehavior.ID, new UpsideDownCameraBehavior());
		register(RandomJumpBehavior.ID, new RandomJumpBehavior());

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
