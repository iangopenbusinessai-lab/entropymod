package com.entropymod.entropy.growth;

import com.entropymod.entropy.EffectHooks;
import com.entropymod.entropy.behavior.SlashedPocketsBehavior;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Slashed Pockets' backstop: ejects anything that ends up in a locked slot.
 *
 * <p>The third of this effect's three enforcement points, and the only one that
 * makes "inaccessible" unconditionally true. {@code Slot.mayPlace} covers manual
 * placement and {@code Inventory.getFreeSlot} covers automatic pickup, but
 * neither sees a slot written directly -- {@code /item replace}, {@code /give}
 * into a specific slot, another mod, or any vanilla path that calls
 * {@code setItem}. This does, because it does not care how the stack arrived.
 *
 * <p><b>Deliberately a tick service rather than another mixin.</b> That is the
 * established third shape in this project alongside attribute and mixin (see
 * {@code GreenThumbGrowth} and {@code BlightTouchedTrample}), and it is the
 * right one here for the same reason: the question is "what is in this player's
 * inventory right now", not a value vanilla is already computing at a hookable
 * point.
 *
 * <p><b>Cost is near zero for players without the effect.</b> The acquired-set
 * check comes first and is a hash lookup; only a player who actually holds
 * Slashed Pockets pays for the 18 slot reads, and only a non-empty slot costs
 * anything beyond that.
 *
 * <p>Runs every tick rather than on a slower cadence on purpose: this is what
 * closes the gap between "an item got in" and "the player used it", and a
 * once-a-second sweep would leave a window in which the locked half is briefly
 * real storage.
 */
public final class SlashedPocketsSweep {

	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!EffectHooks.hasSlashedPockets(player)) {
				continue;
			}
			Inventory inventory = player.getInventory();
			for (int slot = SlashedPocketsBehavior.FIRST_LOCKED_SLOT;
					slot <= SlashedPocketsBehavior.LAST_LOCKED_SLOT; slot++) {
				ItemStack stack = inventory.getItem(slot);
				if (stack.isEmpty()) {
					continue;
				}
				inventory.setItem(slot, ItemStack.EMPTY);
				// A normal player drop, so it can be picked straight back up into a
				// slot that still works rather than being destroyed.
				player.drop(stack, false);
			}
		}
	}

	private SlashedPocketsSweep() {}
}
