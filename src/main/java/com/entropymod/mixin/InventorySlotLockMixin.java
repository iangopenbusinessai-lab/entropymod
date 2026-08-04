package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Slashed Pockets: refuses manual placement into a locked slot.
 *
 * <p>{@code Slot.container} is a {@code public final Container} and
 * {@code Slot.index} is a {@code public int}, so the owning player is reachable
 * without an accessor -- a player-inventory slot's container is the
 * {@code Inventory} itself, whose {@code player} field is also public. The
 * {@code instanceof Inventory} test is therefore what scopes this to the
 * player's own inventory: chests, furnaces and every other container share this
 * class and are untouched.
 *
 * <p><b>{@code index} here is the slot's index within its container</b>, which
 * for the player inventory is the same numbering
 * {@code SlashedPocketsBehavior.isLocked} uses. It is deliberately not the
 * menu-relative slot id, which differs per screen.
 *
 * <p>Injecting at RETURN rather than HEAD so this can only ever <em>remove</em>
 * permission: a slot vanilla already refuses stays refused.
 *
 * <p><b>Client-side note.</b> {@code EffectHooks} returns "no effect" on the
 * client -- the acquired set is server-only state -- so client-side prediction
 * still allows the placement and the server then corrects it, which reads as the
 * item snapping back. That is a cosmetic mismatch, not a hole: the server is
 * authoritative here and {@code SlashedPocketsSweep} is the unconditional
 * backstop. See CLAUDE.md for why this batch does not otherwise need
 * {@code ClientEffectsPayload}.
 */
@Mixin(Slot.class)
public abstract class InventorySlotLockMixin {

	// Both are declared directly on Slot -- checked, because @Shadow does NOT
	// resolve fields inherited from a superclass and fails at mixin-apply time
	// rather than at compile time. See CLAUDE.md's @Shadow trap note.
	@Shadow
	@org.spongepowered.asm.mixin.Final
	public Container container;

	@Shadow
	public int index;

	@Inject(method = "mayPlace", at = @At("RETURN"), cancellable = true)
	private void entropymod$slashedPockets(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue()) {
			return; // already refused -- never widen permission
		}
		if (this.container instanceof Inventory inventory
				&& EffectHooks.isInventorySlotLocked(inventory.player, this.index)) {
			cir.setReturnValue(false);
		}
	}
}
