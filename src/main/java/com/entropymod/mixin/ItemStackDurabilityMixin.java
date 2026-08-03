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
 * hook therefore covers mining, attacking, shears, flint and steel and armour
 * damage without needing a hook per action.
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
 * so this hook now reads {@code getMaxDamage()} and passes it through. See
 * {@code ClumsyDiggerBehavior} for the formula and for why hitting better tools
 * harder is the intent rather than a bug.
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
		// The stack's own max durability scales the penalty -- better tools lose
		// more, deliberately. Safe to read here: getMaxDamage() is a data-component
		// lookup with no side effects, and it returns 0 for non-damageable items,
		// which the hook turns into no extra damage.
		ItemStack stack = (ItemStack) (Object) this;
		return amount + EffectHooks.rollClumsyDiggerExtraDamage(
				player, player.getRandom(), stack.getMaxDamage());
	}
}
