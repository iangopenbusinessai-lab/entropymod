package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Spills an item on jump for Leaky Pockets.
 *
 * <p>Targets {@code ServerPlayer.jumpFromGround} rather than
 * {@code LivingEntity.jumpFromGround}: it is server-side only, so there is no
 * question of the roll happening twice or disagreeing across sides, and it is
 * vanilla's own "a jump happened" marker -- the place the JUMP statistic is
 * awarded and jump exhaustion applied. It calls {@code super} first, so injecting
 * at TAIL cannot disturb the jump physics.
 *
 * <p><b>Nothing happens for players without the effect:</b> the roll and the
 * effect check both live behind {@code EffectHooks.rollLeakyPockets}, which
 * returns false immediately when the acquired set does not contain it.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerJumpMixin {

	@Inject(method = "jumpFromGround", at = @At("TAIL"))
	private void entropymod$spillItemOnJump(CallbackInfo ci) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		if (!EffectHooks.rollLeakyPockets(player, player.getRandom())) {
			return;
		}

		// Collect occupied slots first so the random pick is uniform over what the
		// player actually has, rather than over all slots (which would usually miss).
		List<Integer> occupied = new ArrayList<>();
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (!player.getInventory().getItem(slot).isEmpty()) {
				occupied.add(slot);
			}
		}
		if (occupied.isEmpty()) {
			return;
		}

		int slot = occupied.get(player.getRandom().nextInt(occupied.size()));
		// One item, not the stack -- see LeakyPocketsBehavior.
		ItemStack dropped = player.getInventory().removeItem(slot, 1);
		if (!dropped.isEmpty()) {
			player.drop(dropped, false, true);
		}
	}
}
