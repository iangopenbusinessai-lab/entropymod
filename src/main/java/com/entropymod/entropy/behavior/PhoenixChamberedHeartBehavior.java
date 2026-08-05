package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Phoenix Chambered Heart (GOOD / SURVIVAL, Tier 2) -- once per run, a killing
 * blow leaves you burning back to life instead of dead.
 *
 * <p>Was <b>Second Chance</b>, which set the player to one heart and stopped
 * there. The trigger, the hook and the once-per-run flag are unchanged; only the
 * granted outcome is different. The id changed with the name, so a save from
 * before this build carries an id this one no longer defines -- handled like any
 * unknown id (skipped with a warning at load, rest of the run intact), exactly as
 * when {@code heavy_footsteps} became {@code exposed}.
 *
 * <h2>The outcome is three real vanilla effects, not a custom heal</h2>
 *
 * <p>All three are granted together at the moment of the save, as ordinary
 * {@code MobEffectInstance}s -- the same objects a potion or a beacon hands out.
 * Nothing here reimplements healing or damage absorption.
 *
 * <table border="1">
 *   <caption>What is granted, and what vanilla's own formulas make of it</caption>
 *   <tr><th>effect</th><th>amplifier</th><th>duration</th><th>real result</th></tr>
 *   <tr><td>Regeneration X</td><td>9</td><td>100t (5s)</td>
 *       <td>1.0 HP <b>every tick</b> -- up to 100 HP of healing</td></tr>
 *   <tr><td>Health Boost X</td><td>9</td><td>600t (30s)</td>
 *       <td><b>+40.0 max health</b> (20 -&gt; 60, i.e. 30 hearts)</td></tr>
 *   <tr><td>Absorption X</td><td>9</td><td>600t (30s)</td>
 *       <td><b>40.0 absorption HP</b> (20 gold hearts), instantly</td></tr>
 * </table>
 *
 * <p><b>Amplifier 9 is level X</b>, confirmed rather than assumed: vanilla builds
 * the displayed level as {@code "potion.potency." + amplifier} (from the
 * {@code makeConcatWithConstants} bootstrap entry in
 * {@code PotionContents.getPotionDescription}), and the shipped lang file has
 * {@code potion.potency.1 = "II"} -- so the displayed level is the amplifier plus
 * one. The arithmetic below says the same thing independently: an amplifier of 9
 * multiplies each per-level amount by ten.
 *
 * <h3>Where the numbers come from</h3>
 *
 * <p><b>Health Boost and Absorption are attribute modifiers, scaled per level by
 * one shared rule.</b> {@code MobEffect$AttributeTemplate.create(int amplifier)}
 * is exactly {@code new AttributeModifier(id, amount * (amplifier + 1), operation)}.
 * Both effects register {@code amount = 4.0} with {@code ADD_VALUE} -- Health
 * Boost on {@code MAX_HEALTH}, Absorption on {@code MAX_ABSORPTION} -- so at
 * amplifier 9 both are {@code 4.0 x 10 = 40.0}.
 *
 * <p>Absorption additionally <em>fills</em> what it grants:
 * {@code AbsorptionMobEffect.onEffectStarted} is
 * {@code setAbsorptionAmount(max(getAbsorptionAmount(), 4 * (amplifier + 1)))},
 * so the 40 HP arrive immediately rather than needing to be healed into. Health
 * Boost does not do this -- it raises the ceiling only, which is why
 * Regeneration is the third grant rather than a flourish.
 *
 * <p><b>Regeneration X ticks every single tick</b>, which is the one number here
 * that is easy to get wrong. {@code RegenerationMobEffect} is:
 *
 * <pre>
 *   shouldApplyEffectTickThisTick(duration, amplifier) {
 *       int i = 50 &gt;&gt; amplifier;
 *       return i &gt; 0 ? duration % i == 0 : true;   // 50 &gt;&gt; 9 == 0  -&gt;  every tick
 *   }
 *   applyEffectTick(...) { if (health &lt; maxHealth) heal(1.0f); }
 * </pre>
 *
 * <p>So the interval does not merely get short, it <b>saturates</b> -- at
 * amplifier 5 and above the shift has already reached zero and larger amplifiers
 * change nothing. Over 100 ticks that is up to 100 HP of healing, far more than
 * the 59 needed to fill the boosted bar from {@link #SURVIVE_HEALTH}; the fill
 * completes in about 59 ticks (~3s) and the remaining ticks are no-ops because
 * {@code applyEffectTick} checks {@code health < maxHealth} first.
 *
 * <p><b>Net effect of the save:</b> 60 max health, filled within about three
 * seconds, on top of 40 absorption available immediately -- a 100 HP pool at the
 * instant the killing blow was refused.
 *
 * <h2>Three more grants, on the same trigger</h2>
 *
 * <p>Added to the same moment, applied together with the three above rather than
 * on any schedule of their own.
 *
 * <table border="1">
 *   <caption>The rest of the kit</caption>
 *   <tr><th>grant</th><th>amplifier</th><th>duration</th><th>real result</th></tr>
 *   <tr><td>Speed III</td><td>2</td><td>600t (30s)</td>
 *       <td>{@code +0.6} {@code ADD_MULTIPLIED_TOTAL} on {@code MOVEMENT_SPEED}
 *           -- a <b>x1.6</b> multiplier, i.e. 4.3172 -&gt; <b>6.9075 b/s</b>
 *           walking</td></tr>
 *   <tr><td>Blindness</td><td>0</td><td>100t (5s)</td>
 *       <td>no attribute template and no amplifier-sensitive behaviour at all --
 *           see {@link #BLINDNESS_AMPLIFIER}</td></tr>
 *   <tr><td>{@code entity.wither.death}</td><td>--</td><td>--</td>
 *       <td>played to the rescued player only, at their own position</td></tr>
 * </table>
 *
 * <p><b>Speed III's number is exact, not simulated.</b> Vanilla registers the
 * Speed effect as {@code MOVEMENT_SPEED}, {@code 0.20000000298023224},
 * {@code ADD_MULTIPLIED_TOTAL} (javap-verified from {@code MobEffects}'
 * {@code <clinit>}), and {@code AttributeTemplate.create} scales that by
 * {@code amplifier + 1}, giving {@code 0.6000000089406967} at amplifier 2. Ground
 * speed is linear in the attribute, so the attribute multiplier <em>is</em> the
 * speed multiplier; {@link #speedGrantedAmount} records the derivation.
 *
 * <p>Because the operation is {@code ADD_MULTIPLIED_TOTAL} it composes as a
 * product with every other modifier in that pass rather than summing into one.
 * Two consequences worth stating: sprinting during the window is
 * {@code x1.3 x 1.6 = x2.08} of walking base, and a run also holding <b>Slippery
 * Grip</b> keeps that curse intact -- its compensator is a separate factor in the
 * same product, so sprinting stays at half the (now boosted) walking speed
 * instead of one effect cancelling the other.
 *
 * <p><b>The sound is player-only, deliberately.</b>
 * {@code SoundEvents.WITHER_DEATH} ({@code entity.wither.death}) is delivered as
 * a {@code ClientboundSoundPacket} straight to the rescued player's connection,
 * so nobody else hears it. Every {@code Level.playSound} overload is a broadcast
 * -- its {@code Entity} parameter is the player to <em>exclude</em>, not the one
 * to target -- and vanilla's own {@code Player.playServerSideSound} passes
 * {@code null} there, i.e. tells everyone. This project is singleplayer-scoped,
 * so the personal version is the honest default; broadcasting instead is a
 * one-line change to
 * {@code level.playSound(null, x, y, z, SoundEvents.WITHER_DEATH, SoundSource.PLAYERS, 1f, 1f)}.
 *
 * <h2>Setting the health is still mandatory</h2>
 *
 * <p>None of the three grants can replace it. The hook runs <em>after</em> health
 * has been reduced to zero; Health Boost raises the ceiling without raising
 * current health, Absorption is a separate pool that death does not consult, and
 * Regeneration's first tick does not happen until the effect ticks. A player left
 * at zero is dead again immediately. {@link #SURVIVE_HEALTH} is therefore set
 * first, at 1.0 -- the same value and the same reason as vanilla's own Totem of
 * Undying branch -- and Regeneration takes it from there.
 *
 * <h2>Composition with the permanent health effects: additive, no conflict</h2>
 *
 * <p>Health Boost is an ordinary {@code ADD_VALUE} modifier on {@code MAX_HEALTH},
 * which is the same attribute and the same operation Thick Hide (+4.0), Brittle
 * Bones (-4.0) and Glass Cannon Pact (-2.0) use. They cannot conflict, for the
 * reason recorded for the fall-damage trio: <b>every modifier carries its own
 * {@code Identifier}</b> -- {@code minecraft:effect.health_boost} against
 * {@code entropymod:effect/<id>} -- so they coexist on one {@code AttributeInstance}
 * and {@code calculateValue} sums the whole {@code ADD_VALUE} pool. A run holding
 * Glass Cannon Pact and Brittle Bones is at 14.0 normally and 54.0 for the 30
 * seconds this is up.
 *
 * <p>The permanent effects are unaffected by the temporary one arriving or
 * leaving, in both directions: the mod's modifiers are applied with
 * {@code addOrUpdateTransientModifier} keyed on their own ids and are never
 * touched by {@code MobEffect.removeAttributeModifiers}, which removes strictly
 * by the template's own id.
 *
 * <h2>Expiry is vanilla's, and leaves nothing dangling</h2>
 *
 * <p>Verified end to end rather than assumed, because a temporary max-health
 * bonus is exactly the shape that strands a player at more health than they can
 * have. On the tick an effect expires, {@code MobEffect.removeAttributeModifiers}
 * drops its modifier, which marks the instance dirty, and {@code LivingEntity}'s
 * effect loop ends in {@code refreshDirtyAttributes()} -&gt;
 * {@code onAttributeUpdated(...)}, whose first two branches are:
 *
 * <pre>
 *   if (MAX_HEALTH)         { if (getHealth() &gt; max) setHealth(max); }
 *   if (MAX_ABSORPTION)     { if (getAbsorptionAmount() &gt; max) setAbsorptionAmount(max); }
 * </pre>
 *
 * <p>{@code MAX_ABSORPTION}'s registered default is <b>0.0</b>, so the absorption
 * pool drains to nothing on expiry with no bookkeeping of ours. Regeneration
 * holds no attribute modifier at all and simply stops. <b>There is no mod-side
 * timer, and nothing to clean up</b> -- which is the whole reason for granting
 * real effects instead of imitating them.
 */
public final class PhoenixChamberedHeartBehavior extends HookEffectBehavior {

	public static final String ID = "phoenix_chambered_heart";

	/**
	 * Health the player is put on before the effects land. 1.0 raw = half a heart,
	 * matching vanilla's Totem of Undying exactly.
	 *
	 * <p>Not decoration and not tuning: the hook runs at zero health, and none of
	 * the three granted effects raises current health on the tick they are added.
	 * See the class javadoc.
	 */
	public static final float SURVIVE_HEALTH = 1.0f;

	/**
	 * Ticks of immunity afterwards, so the blow that would have killed cannot
	 * simply land again on the next tick. {@code Entity.invulnerableTime} is
	 * vanilla's own damage-cooldown field -- the same one a normal hit sets to 20
	 * -- so this is 3 seconds of the mechanic that already exists rather than a
	 * bespoke invulnerability state.
	 */
	public static final int INVULNERABLE_TICKS = 60;

	/**
	 * Displayed level X for all three grants. Raw amplifier is one less than the
	 * displayed level; see the class javadoc for how that was confirmed.
	 */
	public static final int AMPLIFIER = 9;

	/** Regeneration X: 5 seconds. Heals every tick at this amplifier. */
	public static final int REGENERATION_TICKS = 100;

	/** Health Boost X: 30 seconds of +40.0 max health. */
	public static final int HEALTH_BOOST_TICKS = 600;

	/** Absorption X: 30 seconds of 40.0 absorption HP, granted filled. */
	public static final int ABSORPTION_TICKS = 600;

	/**
	 * Per-level amount both Health Boost and Absorption register, javap-verified
	 * from {@code MobEffects}' {@code <clinit>}. Multiplied by
	 * {@code amplifier + 1} by {@code AttributeTemplate.create}.
	 */
	public static final double PER_LEVEL_AMOUNT = 4.0;

	/**
	 * Speed III -- displayed level three, so the raw amplifier is two, by the same
	 * {@code "potion.potency." + amplifier} convention confirmed for
	 * {@link #AMPLIFIER}. Unlike the three grants above this one is well inside the
	 * range vanilla's own lang file covers ({@code potion.potency.0} through
	 * {@code .5}), so it needs no translation key of the mod's own.
	 */
	public static final int SPEED_AMPLIFIER = 2;

	/** Speed III: 30 seconds, matching the Health Boost and Absorption window. */
	public static final int SPEED_TICKS = 600;

	/**
	 * Per-level amount vanilla's Speed effect registers, javap-verified from
	 * {@code MobEffects}' {@code <clinit>}: {@code MOVEMENT_SPEED},
	 * {@code 0.20000000298023224}, {@code ADD_MULTIPLIED_TOTAL}.
	 *
	 * <p><b>The operation is what makes the resulting number exact.</b>
	 * {@code AttributeInstance.calculateValue}'s third pass is a product
	 * ({@code e *= 1 + amount} per modifier), so this composes multiplicatively
	 * with vanilla's sprint bonus and with Slippery Grip's compensator rather than
	 * summing into either.
	 */
	public static final double SPEED_PER_LEVEL_AMOUNT = 0.20000000298023224;

	/**
	 * What Speed III is actually worth: {@code 0.2 x (2 + 1) = 0.6}, i.e. a
	 * {@code x1.6} multiplier on the movement-speed total.
	 *
	 * <p>Ground speed is <em>linear</em> in the attribute -- vanilla's friction
	 * recurrence settles at {@code v = a / (1 - 0.546)} with
	 * {@code a = speed x 0.21600002/0.6^3 x 0.98} -- so {@code x1.6} on the
	 * attribute is exactly {@code x1.6} on blocks per second, with no simulation
	 * needed to convert. Against the player's 0.1 base that is
	 * {@code 4.3172 -> 6.9075 b/s} walking and {@code 5.6123 -> 8.9797 b/s}
	 * sprinting.
	 */
	public static double speedGrantedAmount() {
		return SPEED_PER_LEVEL_AMOUNT * (SPEED_AMPLIFIER + 1);
	}

	/**
	 * Blindness: <b>amplifier 0, and no other value would mean anything.</b>
	 *
	 * <p>Verified rather than assumed, because "level I" is a guess elsewhere.
	 * {@code MobEffects}' {@code <clinit>} registers blindness as a bare
	 * {@code new MobEffect(MobEffectCategory.HARMFUL, 2039587)} -- the plain class,
	 * not a subclass, with <b>no {@code addAttributeModifier} call attached</b>.
	 * So it carries no {@code AttributeTemplate} to scale by
	 * {@code amplifier + 1} and overrides no tick behaviour that could read the
	 * amplifier; every consumer treats it as the boolean
	 * {@code hasEffect(BLINDNESS)}. A higher amplifier would change the tooltip
	 * text and nothing else.
	 */
	public static final int BLINDNESS_AMPLIFIER = 0;

	/** Blindness: 5 seconds -- long enough to sell the moment, short enough to fight out of. */
	public static final int BLINDNESS_TICKS = 100;

	/**
	 * Volume and pitch for the Wither death sting. Volume 1.0 is vanilla's own
	 * default for an entity sound; since the packet is addressed to the rescued
	 * player and positioned on them, they hear it at full strength regardless of
	 * {@code SoundEvent.getRange}.
	 */
	public static final float SOUND_VOLUME = 1.0f;

	/** @see #SOUND_VOLUME */
	public static final float SOUND_PITCH = 1.0f;

	/** What one of those effects is actually worth at {@link #AMPLIFIER}. */
	public static double grantedAmount() {
		return PER_LEVEL_AMOUNT * (AMPLIFIER + 1);
	}

	/**
	 * Vanilla's Regeneration cadence, reproduced so the harness can drive it
	 * against the shipped amplifier rather than restating a remembered number.
	 *
	 * @return ticks between heals, or 0 when the effect heals every tick
	 */
	public static int regenerationInterval(int amplifier) {
		return 50 >> amplifier;
	}
}
