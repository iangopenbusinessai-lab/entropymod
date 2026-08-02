package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scales crop growth near a player for Green Thumb / Blight Touched.
 *
 * <p>Targets the static {@code CropBlock.getGrowthSpeed}, whose return value
 * vanilla divides into its growth roll:
 * {@code random.nextInt((int)(25.0F / speed) + 1) == 0}.
 *
 * <p><b>Deliberately not {@code randomTick}.</b> {@code BeetrootBlock} and
 * {@code TorchflowerCropBlock} both override {@code randomTick} -- the exact
 * subclass-override trap CLAUDE.md records -- and although both happen to call
 * {@code super}, relying on that is luck. {@code getGrowthSpeed} has no override
 * risk (it is static) and is called from <b>three</b> places instead of one:
 * {@code CropBlock}, {@code StemBlock} and {@code PitcherCropBlock}. So this
 * single hook covers wheat, carrots, potatoes, beetroot, torchflower, pumpkin and
 * melon stems, and the pitcher crop.
 *
 * <p><b>Players without either effect are unaffected.</b>
 * {@code cropGrowthMultiplier} returns exactly {@code 1.0f} when no nearby player
 * has one, and multiplying by 1.0f leaves vanilla's float untouched. The nearby
 * lookup is skipped entirely off-server.
 */
@Mixin(CropBlock.class)
public abstract class CropGrowthMixin {

	@Inject(method = "getGrowthSpeed", at = @At("RETURN"), cancellable = true)
	private static void entropymod$scaleGrowthSpeed(Block block, BlockGetter blockGetter, BlockPos pos,
													CallbackInfoReturnable<Float> cir) {
		if (!(blockGetter instanceof Level level)) {
			return;
		}
		float scale = EffectHooks.cropGrowthMultiplier(level, pos);
		if (scale != 1.0f) {
			cir.setReturnValue(cir.getReturnValue() * scale);
		}
	}
}
