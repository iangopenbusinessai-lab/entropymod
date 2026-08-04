package com.entropymod.entropy.behavior;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Slashed Pockets (BAD / GEAR, Tier 2) -- half your inventory is gone for good.
 *
 * <h2>Which slots, in this version's layout</h2>
 *
 * <p>Read from the live {@code Inventory} class rather than assumed:
 * {@code INVENTORY_SIZE = 36}, {@code SELECTION_SIZE = 9}, and
 * {@code isHotbarSlot} is true for exactly 0-8. Equipment lives outside that
 * range entirely ({@code SLOT_OFFHAND = 40}, {@code SLOT_BODY_ARMOR = 41},
 * {@code SLOT_SADDLE = 42}), so it is untouched by index alone.
 *
 * <p>The 36 main slots are drawn as four rows of nine. Top to bottom on screen
 * they are:
 *
 * <table border="1">
 *   <caption>Main inventory rows</caption>
 *   <tr><th>row</th><th>slots</th><th>locked?</th></tr>
 *   <tr><td>storage, top</td><td>9-17</td><td><b>yes</b></td></tr>
 *   <tr><td>storage, middle</td><td>18-26</td><td><b>yes</b></td></tr>
 *   <tr><td>storage, bottom</td><td>27-35</td><td>no</td></tr>
 *   <tr><td>hotbar</td><td>0-8</td><td>no</td></tr>
 * </table>
 *
 * <p>So "the upper half" is <b>slots 9-26</b>: the top two rows as displayed,
 * and exactly 18 of the 36 main slots. <b>The reading is not the only possible
 * one and is recorded deliberately</b> -- "half the inventory" could also have
 * meant half of the 27-slot storage area, which is 13.5 slots and would cut a
 * row in two. Splitting on a row boundary at the true halfway point of the whole
 * main inventory is the only interpretation that is both exactly half and
 * visually clean.
 *
 * <h2>Three enforcement points, because one is not enough</h2>
 *
 * <ul>
 *   <li><b>Manual placement</b> -- {@code Slot.mayPlace}, so dragging or
 *       shift-clicking into a locked slot is refused.</li>
 *   <li><b>Automatic pickup</b> -- {@code Inventory.getFreeSlot}, so walking over
 *       an item never files it into a locked slot. Without this the slots would
 *       fill constantly and the sweep below would be spitting items out
 *       endlessly.</li>
 *   <li><b>A per-tick sweep</b> -- the backstop, and the only one that makes
 *       "inaccessible" unconditionally true. Commands, other mods and any vanilla
 *       path that writes a slot directly all bypass the first two;
 *       {@code SlashedPocketsSweep} ejects whatever lands there regardless of how
 *       it arrived.</li>
 * </ul>
 *
 * <h2>The one-time drop</h2>
 *
 * <p>Whatever is in those slots when the effect is acquired is dropped at the
 * player's feet, once. This is gated on {@link EffectContext#isFreshPick()},
 * <b>not</b> on the slots being empty -- {@code apply} runs again on every
 * respawn, rejoin and dimension change, and re-running the drop there would be
 * harmless only by accident (the sweep keeps the slots empty), which is a poor
 * reason for a side effect to be correct.
 */
public final class SlashedPocketsBehavior implements EffectBehavior {

	public static final String ID = "slashed_pockets";

	/** First locked slot: the top-left storage slot, directly above the hotbar rows. */
	public static final int FIRST_LOCKED_SLOT = 9;

	/** Last locked slot, inclusive. 9..26 is 18 slots -- exactly half of the 36. */
	public static final int LAST_LOCKED_SLOT = 26;

	/** Slots 9-26 inclusive. Equipment indices (40+) are outside this by construction. */
	public static boolean isLocked(int slot) {
		return slot >= FIRST_LOCKED_SLOT && slot <= LAST_LOCKED_SLOT;
	}

	/** How many slots this takes away. Derived, so it cannot disagree with the bounds. */
	public static int lockedSlotCount() {
		return LAST_LOCKED_SLOT - FIRST_LOCKED_SLOT + 1;
	}

	@Override
	public void apply(EffectContext ctx) {
		if (!ctx.isFreshPick()) {
			return; // see the class javadoc -- the drop is a one-time event, not a state
		}
		ServerPlayer player = ctx.target();
		Inventory inventory = player.getInventory();
		int dropped = 0;
		for (int slot = FIRST_LOCKED_SLOT; slot <= LAST_LOCKED_SLOT; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			inventory.setItem(slot, ItemStack.EMPTY);
			// Not `false, true`: these should behave like a normal player drop so
			// they can be picked straight back up into the slots that still work.
			player.drop(stack, false);
			dropped++;
		}
		if (dropped > 0) {
			ctx.tell("[Entropy] Your pockets tear open -- " + dropped + " stack(s) spill out.");
		}
		EntropyMod.LOGGER.info("Slashed Pockets: locked slots {}-{} for {}, dropped {} stack(s).",
				FIRST_LOCKED_SLOT, LAST_LOCKED_SLOT, player.getName().getString(), dropped);
	}

	@Override
	public void remove(EffectContext ctx) {
		// Nothing to undo: the lock is derived from membership in AcquiredEffects,
		// so dropping out of that set is itself the removal. The dropped items are
		// gone in the same way any dropped item is.
	}
}
