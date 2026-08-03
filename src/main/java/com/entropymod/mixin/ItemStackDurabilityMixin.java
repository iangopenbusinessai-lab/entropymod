package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

/**
 * Adds occasional extra tool wear for Clumsy Digger.
 *
 * <p>Targets {@code ItemStack.hurtAndBreak(int, ServerLevel, ServerPlayer,
 * Consumer)}, verified as the single choke point for durability loss: the
 * {@code (int, LivingEntity, EquipmentSlot)} and
 * {@code (int, LivingEntity, InteractionHand)} overloads both funnel into it. One
 * hook therefore reaches every action that costs durability without needing a
 * hook per action.
 *
 * <p><b>Which means the hook is broader than the effect, and the difference is
 * not this mixin's to resolve.</b> Armour and the elytra lose durability through
 * this same method. The item filter lives in {@code ClumsyDiggerBehavior} behind
 * {@code EffectHooks}, keeping this mixin to the one-line, decides-nothing shape
 * the rest of them follow.
 *
 * <p>The {@code ServerPlayer} parameter is <b>nullable</b> -- mob-held equipment
 * loses durability through the same method -- and that null is precisely the gate
 * that keeps this scoped to players. {@code EffectHooks} handles it.
 *
 * <p><b>Players without the effect are unaffected:</b>
 * {@code rollClumsyDiggerExtraDamage} returns 0, and {@code amount + 0} is the
 * original value.
 *
 * <p><b>The extra damage is proportional to the stack's own max durability</b>,
 * so the whole stack is passed through rather than just the amount. See
 * {@code ClumsyDiggerBehavior} for the formula, for the tag that scopes it to
 * mining tools, and for why hitting better tools harder is the intent rather
 * than a bug.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackDurabilityMixin {

	@ModifyVariable(
			method = "hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V",
			at = @At("HEAD"),
			argsOnly = true,
			ordinal = 0)
	private int entropymod$extraToolWear(int amount, int ignoredAmount, ServerLevel level,
										  ServerPlayer player, Consumer<Item> onBreak) {
		if (player == null) {
			return amount;
		}
		// The whole stack goes through, not just its durability: the hook decides
		// BOTH whether this item is in scope (mining tools only -- armour and the
		// elytra reach this same method) and how much extra to apply, which scales
		// with the stack's own max durability.
		ItemStack stack = (ItemStack) (Object) this;
		return amount + EffectHooks.rollClumsyDiggerExtraDamage(
				player, player.getRandom(), stack);
	}
}
