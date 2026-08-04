package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Flamboyant (BAD / SURVIVAL, Tier 2) -- any fire kills you outright.
 *
 * <p>The most severe curse in the project so far. It is nonetheless
 * {@code counterplay = true}, and the distinction matters: it cannot kill a
 * player who is not on fire, and every source is avoidable, blockable with a
 * Fire Resistance potion, or cancellable with water. It is lethal, not
 * unavoidable.
 *
 * <h2>Fire specifically, not damage generally</h2>
 *
 * <p>Gated on the vanilla tag {@code #minecraft:is_fire}, whose shipped contents
 * were read out of the jar rather than assumed:
 *
 * <pre>
 *   in_fire, campfire, on_fire, lava, hot_floor,
 *   unattributed_fireball, fireball
 * </pre>
 *
 * <p>Using the tag rather than a hand-written list of damage types means a
 * datapack that adds a fire damage type is covered for free, and it is the same
 * discipline {@code ClumsyDiggerBehavior} follows with its item tag.
 *
 * <p><b>Everything outside that tag is untouched</b> -- falling, drowning,
 * mobs, cacti, the void all behave exactly as they would without this effect.
 * That is asserted directly in the harness rather than left to inspection,
 * because "kills you on any damage" is the obvious way to get this wrong and it
 * would be indistinguishable in casual play from the intended behaviour.
 *
 * <h2>How the kill happens</h2>
 *
 * <p>The fire damage is <b>amplified to a certainly-lethal amount</b> rather than
 * the player being killed by a separate call. That keeps vanilla in charge of
 * everything that follows: the correct death message ("burned to death", "tried
 * to swim in lava"), the death sound, drops, statistics, and the advancement
 * triggers. A direct {@code kill()} would report a generic death for a very
 * specific cause.
 *
 * <p>The multiplier is applied to max health rather than being a fixed number so
 * that armour, absorption, resistance and Iron Skin -- all of which reduce the
 * amount after this point -- cannot leave the player alive on a sliver.
 */
public final class FlamboyantBehavior extends HookEffectBehavior {

	public static final String ID = "flamboyant";

	/**
	 * Lethal damage is {@code maxHealth * this}. Deliberately enormous and
	 * deliberately finite: armour caps at 80% reduction and Resistance at a
	 * further 80%, so the headroom has to be large -- but {@code Float.MAX_VALUE}
	 * risks non-finite arithmetic downstream in vanilla's own damage maths.
	 */
	public static final float LETHAL_HEALTH_MULTIPLE = 1000.0f;

	/**
	 * The damage a fire hit becomes. Pure, so the harness can check it without a
	 * live player -- see {@code BehemothGauntletsBehavior.damageFor} for why the
	 * decision is split out of the hook.
	 *
	 * <p>{@code max} rather than a plain replacement so this can only ever raise
	 * the damage: a fire source that was somehow already more lethal is left
	 * alone rather than being scaled down.
	 */
	public static float lethalDamage(float damage, float maxHealth) {
		return Math.max(damage, maxHealth * LETHAL_HEALTH_MULTIPLE);
	}
}
