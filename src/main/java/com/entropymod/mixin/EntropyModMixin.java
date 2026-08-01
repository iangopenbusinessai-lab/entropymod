package com.entropymod.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Placeholder mixin, kept as a working example. Not needed for the core
 * timer/GUI/networking loop -- but signature effects like "Mirror World"
 * (invert movement input) or "Fisheye" (force FOV) will need real mixins
 * into client input/render classes. This shows the pattern is wired up
 * and ready for that later work.
 */
@Mixin(MinecraftServer.class)
public class EntropyModMixin {
	@Inject(at = @At("HEAD"), method = "loadLevel")
	private void init(CallbackInfo info) {
		// This code is injected into the start of MinecraftServer.loadLevel()V
	}
}
