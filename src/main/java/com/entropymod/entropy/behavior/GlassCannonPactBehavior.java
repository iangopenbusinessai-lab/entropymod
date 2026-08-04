package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import com.entropymod.entropy.EffectContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.List;

/**
 * Glass Cannon Pact (GOOD / COMBAT, entropy 40-60) -- hit half again as hard,
 * with one heart less to survive on.
 *
 * <p>Deliberately placed at the <b>top of Tier 2 and into what will be Tier 3</b>
 * rather than at 25, because the health cost is permanent and compounds with
 * everything else a long run has accumulated by then.
 *
 * <h2>The health floor is safe, and stays safe stacked</h2>
 *
 * <p>{@code MAX_HEALTH} is clamped to {@code [1.0, 1024.0]} -- both
 * {@code sanitizeValue(0)} and {@code sanitizeValue(-5)} return {@code 1.0}. So
 * unlike {@code GRAVITY}, whose floor of -1.0 makes over-stacking catastrophic,
 * <b>stacked health penalties bottom out harmlessly at half a heart</b> and the
 * attribute system can never kill the player by itself.
 *
 * <p>Checked explicitly rather than by analogy, since Brittle Bones is the one
 * effect this can meet:
 *
 * <table border="1">
 *   <caption>Max health</caption>
 *   <tr><th>held</th><th>modifier sum</th><th>max health</th></tr>
 *   <tr><td>vanilla</td><td>0</td><td>20.0 (10 hearts)</td></tr>
 *   <tr><td>this alone</td><td>-2.0</td><td>18.0 (9 hearts)</td></tr>
 *   <tr><td>Brittle Bones alone</td><td>-4.0</td><td>16.0 (8 hearts)</td></tr>
 *   <tr><td><b>both</b></td><td><b>-6.0</b></td><td><b>14.0 (7 hearts)</b></td></tr>
 * </table>
 *
 * <p>They are also in different categories (COMBAT and SURVIVAL), so
 * anti-stacking does not separate them -- the pairing is ordinary, not an edge
 * case.
 *
 * <h2>Current health has to be clamped, same as Brittle Bones</h2>
 *
 * <p>Lowering max health does <b>not</b> lower current health: a player at 20/20
 * becomes 20/18 and displays more hearts than they have until something calls
 * {@code setHealth}. {@link #afterApply} fixes that immediately. It is idempotent
 * -- clamping an already-clamped value is a no-op -- which matters because
 * {@code apply} runs again on every respawn, rejoin and dimension change.
 *
 * <p><b>ATTACK_DAMAGE is not client-syncable</b>, so the item tooltip will not
 * reflect the +50%. The server applies it regardless; this is the same known
 * cosmetic gap Steady Hands and Weak Grip have.
 */
public final class GlassCannonPactBehavior extends AttributeEffectBehavior {

	public static final String ID = "glass_cannon_pact";

	/** +50% attack damage, ADD_MULTIPLIED_BASE against the player's base of 1.0. */
	public static final double ATTACK_BONUS = 0.50;

	/** -2.0 raw = -1 heart. */
	public static final double HEALTH_PENALTY = -2.0;

	public GlassCannonPactBehavior() {
		super(List.of(
				new Change(Attributes.ATTACK_DAMAGE, ATTACK_BONUS,
						AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
				new Change(Attributes.MAX_HEALTH, HEALTH_PENALTY,
						AttributeModifier.Operation.ADD_VALUE)));
	}

	@Override
	protected void afterApply(EffectContext ctx) {
		ServerPlayer player = ctx.target();
		float max = player.getMaxHealth();
		if (player.getHealth() > max) {
			player.setHealth(max);
		}
	}
}
