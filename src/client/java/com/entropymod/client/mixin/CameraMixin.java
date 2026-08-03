package com.entropymod.client.mixin;

import com.entropymod.client.ClientRunState;
import net.minecraft.client.Camera;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Upside-Down Camera. Rolls the view 180 degrees about the forward axis.
 *
 * <h2>Why this is a one-argument change and not a matrix hack</h2>
 *
 * <p>{@code Camera.setRotation(yRot, xRot)} -- the single call site is
 * {@code Camera.alignWithEntity(float)} -- does, verified in bytecode:
 *
 * <pre>this.rotation.rotationYXZ(PI - yRot * DEG2RAD, -xRot * DEG2RAD, 0.0f);
 *FORWARDS.rotate(rotation, forwards);
 *UP.rotate(rotation, up);
 *LEFT.rotate(rotation, left);</pre>
 *
 * <p><b>The third argument of {@code rotationYXZ} is roll, and vanilla hardcodes
 * it to zero.</b> There is already a roll slot in the camera's own quaternion;
 * this effect just fills it. Redirecting that one call is therefore the whole
 * implementation, and every derived value -- the forward/up/left basis, the
 * cached view-rotation matrix, the view-rotation-projection matrix and the cull
 * frustum -- is recomputed by vanilla's own unchanged code from the quaternion.
 * Nothing downstream needs patching.
 *
 * <h2>Why it stays playable</h2>
 *
 * <p>{@code rotationYXZ(y, x, z)} is {@code Ry * Rx * Rz}, and {@code Rz} is a
 * rotation about the Z axis. {@code Camera.FORWARDS} is {@code (0, 0, -1)},
 * which lies <em>on</em> that axis, so <b>the forward vector is unchanged</b>
 * while {@code UP} and {@code LEFT} flip. This is a pure roll about the view
 * axis, which has one consequence that matters more than any other:
 *
 * <p><b>The centre of the screen still points at exactly the same block.</b>
 * Crosshair targeting, mining, attacking and item use are completely unaffected
 * -- the raycast comes from the player entity's view vector, not the camera's
 * roll. The world is presented upside-down; what you are pointing at does not
 * move. The HUD is rendered in screen space and is unaffected too, so health,
 * hunger and the hotbar stay readable.
 *
 * <p>Mouse look still works, with both axes reading as inverted -- which is what
 * an upside-down view should feel like, and is coherent rather than broken.
 *
 * <p><b>Reverting is one constant.</b> If this proves genuinely sickening rather
 * than merely disorienting, set {@link #ROLL_RADIANS} to {@code 0.0f} and the
 * effect becomes a no-op without touching any other code.
 *
 * <p><b>Players without the effect are unaffected:</b> the redirect passes
 * vanilla's own {@code roll} argument straight through.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

	/** 180 degrees. The only tuning knob this effect has -- see the class javadoc. */
	private static final float ROLL_RADIANS = (float) Math.PI;

	@Redirect(
			method = "setRotation",
			at = @At(value = "INVOKE",
					target = "Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;"))
	private Quaternionf entropymod$rollCamera(Quaternionf rotation, float yaw, float pitch, float roll) {
		return rotation.rotationYXZ(yaw, pitch,
				ClientRunState.hasUpsideDownCamera() ? ROLL_RADIANS : roll);
	}
}
