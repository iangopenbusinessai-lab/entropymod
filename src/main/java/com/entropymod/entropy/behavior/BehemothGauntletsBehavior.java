package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Behemoth Gauntlets (GOOD / COMBAT, Tier 2) -- devastating bare-handed,
 * useless with a weapon.
 *
 * <p>+{@link #UNARMED_BONUS} flat damage when the main hand is empty;
 * {@link #ARMED_MULTIPLIER} of normal damage otherwise. A vanilla unarmed hit is
 * 1, so this turns a punch into 21 -- roughly three diamond swords -- while a
 * diamond sword itself drops from ~7 to ~1.75.
 *
 * <h2>Why this is a hook and not an attribute</h2>
 *
 * <p>{@code ATTACK_DAMAGE} cannot express it. The attribute is a single number
 * read once per swing and it already <em>includes</em> the held weapon's own
 * modifier, so there is no value of it that is +20 empty-handed and -75% armed.
 * The distinction only exists at the moment of the attack.
 *
 * <p>The hook is {@code Player.attack}'s call to
 * {@code Entity.hurtOrSimulate(DamageSource, float)} -- see
 * {@code PlayerAttackDamageMixin}. That is the single point where the fully
 * computed melee damage is handed to the target, after the attribute, the
 * cooldown scale, enchantments and the crit multiplier.
 *
 * <h2>What counts as "unarmed" -- and it is broader than "no weapon"</h2>
 *
 * <p>The discriminator is {@code Player.getWeaponItem().isEmpty()}, which for a
 * player resolves to the main-hand stack. <b>So holding anything at all --
 * a sword, a pickaxe, a stack of dirt -- counts as armed</b> and takes the
 * penalty. That is deliberate: "is your hand empty" is the only distinction
 * available at this point that does not require inventing a definition of
 * "weapon", and it makes the effect legible ("fight with your fists") rather
 * than dependent on an item-tag judgement call.
 *
 * <p><b>Known limit:</b> the sweep attack deals its damage through a separate
 * {@code doSweepAttack} call and is not scaled here. Sweeping only happens with
 * a sweep-capable weapon, i.e. only in the armed case that this effect is
 * pushing the player away from anyway.
 */
public final class BehemothGauntletsBehavior extends HookEffectBehavior {

	public static final String ID = "behemoth_gauntlets";

	/** Flat damage added to a bare-handed hit. */
	public static final float UNARMED_BONUS = 20.0f;

	/** What a hit with anything in hand is worth: -75%. */
	public static final float ARMED_MULTIPLIER = 0.25f;

	/**
	 * The whole decision, as a pure function.
	 *
	 * <p>Split out from {@link com.entropymod.entropy.EffectHooks} deliberately:
	 * the hook needs a live {@code Player} to ask whether the hand is empty, which
	 * a headless harness cannot build, so the two branches would otherwise be
	 * untestable off-server. Here both are driven against the real shipped code.
	 *
	 * @param emptyHanded whether {@code getWeaponItem()} was empty
	 */
	public static float damageFor(float damage, boolean emptyHanded) {
		return emptyHanded ? damage + UNARMED_BONUS : damage * ARMED_MULTIPLIER;
	}
}
