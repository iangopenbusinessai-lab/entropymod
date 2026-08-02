package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes footsteps louder for Heavy Footsteps -- part (a) of that effect.
 *
 * <p>{@code Entity.playStepSound(BlockPos, BlockState)} ends in
 * {@code playSound(soundType.getStepSound(), soundType.getVolume() * 0.15f,
 * soundType.getPitch())}. This redirects that call and scales only the volume,
 * leaving the sound event and pitch alone so footsteps still sound like the block
 * being walked on.
 *
 * <p>This is not purely cosmetic: vanilla derives a sound's audible radius from
 * its volume, so a louder footstep genuinely carries farther to other players.
 * It does <b>not</b> make mobs notice you -- ordinary mob AI has no hearing at
 * all. That half of the effect is {@code LivingEntityVisibilityMixin}; see
 * {@link com.entropymod.entropy.behavior.HeavyFootstepsBehavior} for the full
 * finding.
 *
 * <p>{@code playStepSound} is declared on {@code Entity} and runs for every
 * entity in the world, so the {@code instanceof Player} guard is load-bearing --
 * without it every cow would stomp.
 */
@Mixin(Entity.class)
public abstract class EntityStepSoundMixin {

	@Redirect(
			method = "playStepSound",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"))
	private void entropymod$louderFootsteps(Entity entity, SoundEvent sound, float volume, float pitch) {
		float scale = entity instanceof Player player
				? EffectHooks.stepSoundVolumeMultiplier(player)
				: 1.0f;
		entity.playSound(sound, volume * scale, pitch);
	}
}
