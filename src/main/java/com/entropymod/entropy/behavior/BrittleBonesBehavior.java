package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import com.entropymod.entropy.EffectContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Brittle Bones (BAD / SURVIVAL) -- -2 hearts of maximum health.
 *
 * <p>The one attribute effect in this batch that needs a fixup rather than just
 * a modifier, for two reasons that are worth stating explicitly since both are
 * easy to miss.
 *
 * <p><b>1. The floor is safe.</b> MAX_HEALTH is a RangedAttribute clamped to
 * {@code [1.0, 1024.0]} — javap-verified, and {@code sanitizeValue(-5)} and
 * {@code sanitizeValue(0)} both return {@code 1.0}. So max health can never be
 * driven to zero by stacked penalties: the worst case is half a heart, and the
 * attribute system itself will never kill the player. That is why this effect is
 * marked {@code counterplay = true}.
 *
 * <p><b>2. Current health is not clamped automatically.</b> Lowering max health
 * does not lower current health — a player at 20/20 becomes 20/16, displaying
 * more hearts than they have and only correcting on the next thing that happens
 * to call {@code setHealth}. {@link #afterApply} clamps it immediately so the
 * hearts read correctly the instant the effect lands. This runs on re-application
 * too, which is harmless: clamping an already-clamped value is a no-op.
 */
public final class BrittleBonesBehavior extends AttributeEffectBehavior {

	public static final String ID = "brittle_bones";

	public BrittleBonesBehavior() {
		super(Attributes.MAX_HEALTH, -4.0, AttributeModifier.Operation.ADD_VALUE);
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
