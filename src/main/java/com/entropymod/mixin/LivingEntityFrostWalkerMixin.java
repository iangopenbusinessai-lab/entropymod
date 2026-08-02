package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import com.entropymod.entropy.behavior.FrostWalkerInnateBehavior;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantedItemInUse;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Grants Frost Walker without boots or an enchantment.
 *
 * <p>Targets {@code LivingEntity.onChangedBlock(ServerLevel, BlockPos)}, which is
 * <b>vanilla's own trigger for this</b>: it is the method that calls
 * {@code EnchantmentHelper.runLocationChangedEffects}, which is what makes
 * enchanted boots freeze water. Hooking the same method means this effect fires
 * on exactly the same schedule as the real enchantment rather than on a
 * hand-rolled tick check.
 *
 * <p>Rather than reimplementing the freeze, this resolves <b>vanilla's own Frost
 * Walker enchantment</b> from the registry and runs its real location-changed
 * effects. Every parameter -- radius, block predicate, the frosted-ice state, the
 * on-ground and not-riding requirements -- comes from
 * {@code data/minecraft/enchantment/frost_walker.json}, so a datapack that
 * retunes Frost Walker retunes this too. See
 * {@link FrostWalkerInnateBehavior} for the values as they ship.
 *
 * <p>The {@code EnchantedItemInUse} argument is a required parameter of
 * {@code runLocationChangedEffects} but is <b>unused by the {@code replace_disk}
 * effect</b> -- verified in {@code ReplaceDisk.apply}'s bytecode, which never
 * loads that slot. An empty stack in the feet slot is therefore a faithful stand
 * in for "no boots", which is precisely what this effect is about.
 *
 * <p>{@code onChangedBlock} is declared on {@code LivingEntity} and runs for every
 * living entity, so the {@code instanceof Player} guard is load-bearing -- without
 * it every wandering mob would freeze water.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityFrostWalkerMixin {

	@Inject(method = "onChangedBlock", at = @At("TAIL"))
	private void entropymod$innateFrostWalker(ServerLevel level, BlockPos pos, CallbackInfo ci) {
		if (!((Object) this instanceof Player player) || !EffectHooks.hasInnateFrostWalker(player)) {
			return;
		}
		// Optional: absent only if a datapack removed the enchantment entirely.
		level.registryAccess()
				.lookup(Registries.ENCHANTMENT)
				.flatMap(registry -> registry.get(Enchantments.FROST_WALKER))
				.ifPresent(holder -> entropymod$runFrostWalker(holder, level, player));
	}

	private void entropymod$runFrostWalker(Holder<Enchantment> frostWalker, ServerLevel level, Player player) {
		EnchantedItemInUse noBoots =
				new EnchantedItemInUse(ItemStack.EMPTY, EquipmentSlot.FEET, (LivingEntity) (Object) this);
		frostWalker.value().runLocationChangedEffects(
				level, FrostWalkerInnateBehavior.LEVEL, noBoots, player);
	}
}
