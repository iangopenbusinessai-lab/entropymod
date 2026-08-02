package com.entropymod.mixin;

import com.entropymod.entropy.EntropyAttributes;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds this mod's {@link EntropyAttributes#PICKUP_RANGE} to the player's default
 * attribute set.
 *
 * <p>Without this the attribute exists in the registry but is not present on the
 * player entity, so {@code getAttribute(PICKUP_RANGE)} returns null and
 * {@link com.entropymod.entropy.AttributeEffectBehavior} logs its "targets an
 * attribute the player does not have" error instead of applying anything.
 *
 * <p>Targets {@code Player.createAttributes()}, which is the static builder
 * vanilla registers for {@code EntityType.PLAYER}. Injecting at RETURN and
 * calling {@code add} on the returned builder works because
 * {@code AttributeSupplier.Builder.add} mutates the builder and returns it
 * fluently -- the return value here is the same object vanilla is about to hand
 * to {@code DefaultAttributes}, so the addition lands before the supplier is
 * built.
 *
 * <p>The attribute's default is 1.0 and it is a pure multiplier, so <b>every
 * player gets exactly vanilla pickup behaviour</b> until Magnetic Boots adds a
 * modifier. This mixin changes no behaviour on its own.
 */
@Mixin(Player.class)
public abstract class PlayerAttributesMixin {

	@Inject(method = "createAttributes", at = @At("RETURN"))
	private static void entropymod$addPickupRange(CallbackInfoReturnable<AttributeSupplier.Builder> cir) {
		cir.getReturnValue().add(EntropyAttributes.PICKUP_RANGE);
	}
}
