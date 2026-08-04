package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import com.entropymod.entropy.behavior.SlashedPocketsBehavior;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Slashed Pockets: keeps automatic pickup out of the locked slots.
 *
 * <p>{@code Inventory.getFreeSlot()} is vanilla's "where does a newly picked-up
 * item go" search -- a linear scan of {@code items} returning the first empty
 * index, or -1. Overriding it is what stops a walked-over item being filed into
 * a locked slot.
 *
 * <p><b>Without this the sweep would be doing all the work, and visibly.</b>
 * Items would land in locked slots constantly and be spat back out a tick later,
 * so a player walking over a pile of drops would watch them bounce. Refusing the
 * slot up front is what makes the effect read as "these slots do not exist"
 * rather than "these slots are haunted".
 *
 * <p>Cancels with a replacement result rather than redirecting the scan, so the
 * fallback of -1 ("inventory full") stays vanilla's own value and every caller
 * that special-cases it keeps working.
 */
@Mixin(Inventory.class)
public abstract class InventoryFreeSlotMixin {

	// Declared on Inventory itself -- see the @Shadow-inherited-field note in
	// CLAUDE.md for why that is checked rather than assumed.
	@Shadow
	@org.spongepowered.asm.mixin.Final
	private NonNullList<ItemStack> items;

	@Shadow
	@org.spongepowered.asm.mixin.Final
	public Player player;

	@Inject(method = "getFreeSlot", at = @At("HEAD"), cancellable = true)
	private void entropymod$skipLockedSlots(CallbackInfoReturnable<Integer> cir) {
		if (!EffectHooks.hasSlashedPockets(this.player)) {
			return;
		}
		for (int slot = 0; slot < this.items.size(); slot++) {
			if (!SlashedPocketsBehavior.isLocked(slot) && this.items.get(slot).isEmpty()) {
				cir.setReturnValue(slot);
				return;
			}
		}
		cir.setReturnValue(-1);
	}
}
