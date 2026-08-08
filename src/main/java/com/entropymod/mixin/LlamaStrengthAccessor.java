package com.entropymod.mixin;

import net.minecraft.world.entity.animal.equine.Llama;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches {@code Llama.setStrength(int)}, which vanilla keeps private.
 *
 * <p>A llama's carrying capacity is {@code getStrength() * 3} slots and strength
 * is only ever set by {@code setRandomStrength}, which rolls
 * {@code 1 + nextInt(nextFloat() < 0.04 ? 5 : 3)} -- so a natural llama is
 * strength 1-3 and reaches the maximum of 5 about 1.6% of the time. Emotional
 * Support Llama needs the maximum deterministically.
 *
 * <p><b>An {@code @Invoker} is a materially lower risk class than the injections
 * CLAUDE.md's mixin catalogue is about.</b> It adds no behaviour, changes no
 * control flow and cannot be outvoted by a downstream re-clamp; it only widens
 * access to a method that already exists. The one failure mode it shares with the
 * rest is a wrong target name, which builds green and fails at runtime -- so the
 * name and descriptor were read off {@code javap}, not guessed:
 * {@code private void setStrength(int)}.
 */
@Mixin(Llama.class)
public interface LlamaStrengthAccessor {

	@Invoker("setStrength")
	void entropymod$setStrength(int strength);
}
