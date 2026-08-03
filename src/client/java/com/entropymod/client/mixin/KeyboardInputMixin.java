package com.entropymod.client.mixin;

import com.entropymod.client.ClientRunState;
import com.entropymod.entropy.MovementScramble;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Randomized Movement Controls and Random Jump. Two effects, one hook, because
 * both are "rewrite this tick's key presses before vanilla reads them".
 *
 * <p>Targets {@code KeyboardInput.tick()} at RETURN. Verified in bytecode, that
 * method does exactly two things:
 *
 * <ol>
 *   <li>builds {@code keyPresses = new Input(up, down, left, right, jump, shift,
 *       sprint)} from the {@code KeyMapping}s, and</li>
 *   <li>derives {@code moveVector = new Vec2(calculateImpulse(left, right),
 *       calculateImpulse(forward, backward)).normalized()}.</li>
 * </ol>
 *
 * <p>So injecting at RETURN and rewriting both fields is indistinguishable from
 * the player having physically pressed different keys -- everything downstream
 * (movement, sprinting, the input packet the server receives) follows from these
 * two values and needs no further patching.
 *
 * <p><b>{@code moveVector} must be recomputed, not just {@code keyPresses}.</b>
 * They are derived at the same moment and then read independently:
 * {@code moveVector} drives actual movement while {@code keyPresses} drives
 * sprint/sneak logic and is what gets sent to the server. Permuting one without
 * the other would desync them and produce a player who moves one way while the
 * server is told another.
 *
 * <p><b>Timing is why Random Jump lives here too.</b> {@code LocalPlayer.aiStep}
 * calls {@code this.input.tick()} and then, later in the same method, reads
 * {@code input.keyPresses.jump()}. Setting the jump press from any other
 * per-tick hook would either be overwritten by this method or land a tick late.
 * Vanilla itself uses the same trick -- {@code ClientInput.makeJump()} exists
 * precisely to fake a jump press, and {@code LocalPlayer.aiStep} calls it for
 * auto-jump.
 *
 * <p><b>Only the four movement directions are permuted.</b> Jump, sneak and
 * sprint are passed through untouched -- scrambling those would make the effect
 * something other than what it says.
 *
 * <p><b>Players without either effect are unaffected:</b> {@code moveScramble()}
 * returns {@code ""} and {@code tickForcedJump()} returns false, and the method
 * returns before touching a field.
 *
 * <h2>Why this mixin extends {@link ClientInput} instead of using {@code @Shadow}</h2>
 *
 * <p><b>Both {@code keyPresses} and {@code moveVector} are declared on
 * {@code ClientInput}, not on {@code KeyboardInput}</b> -- javap-verified;
 * {@code KeyboardInput} itself declares only {@code options},
 * {@code calculateImpulse} and {@code tick()}. An earlier version shadowed both
 * fields and <b>crashed the game on every world launch</b> with
 * "@Shadow field keyPresses was not located in the target class".
 *
 * <p><b>{@code @Shadow} on a field resolves ONLY against fields declared
 * directly in the mixin's target class -- it never walks the superclass
 * chain.</b> This is not a quirk of this version; it is Mixin's own
 * {@code TargetClassContext.findAliasedField}, which iterates
 * {@code classNode.fields} of the target and nothing else.
 *
 * <p>The fix is to declare the mixin as extending the target's <i>actual</i>
 * superclass, which Mixin explicitly supports: {@code MixinInfo.Standard
 * .validate} takes an early {@code continue} when the mixin's {@code superName}
 * equals the target's, so this is the fully-attached case, not even a "detached"
 * one. The fields are then plain Java inheritance -- no annotation involved --
 * and {@code protected moveVector} is legally reachable through {@code this}
 * across packages, which is what blocked a simple cast to the target type.
 *
 * <p>Verified end to end in bytecode rather than assumed: javac emits these as
 * {@code GETFIELD/PUTFIELD KeyboardInputMixin.moveVector} (its own class as the
 * owner, the normal encoding for inherited field access), and
 * {@code MixinTargetContext.transformFieldRef} rewrites exactly that case --
 * owner equal to the mixin's class ref -- to the target, giving
 * {@code PUTFIELD KeyboardInput.moveVector} in the merged method. JVM field
 * resolution then walks up to {@code ClientInput}, and the access is legal
 * because the merged code lives in {@code KeyboardInput}, which is in the same
 * package as the protected field.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {

	@Inject(method = "tick", at = @At("RETURN"))
	private void entropymod$scrambleAndForceJump(CallbackInfo ci) {
		String scramble = ClientRunState.moveScramble();
		// Ticked every call so the countdown advances regardless of the scramble.
		boolean forceJump = ClientRunState.tickForcedJump();

		if (scramble.isEmpty() && !forceJump) {
			return;
		}

		Input in = this.keyPresses;
		boolean forward = in.forward();
		boolean backward = in.backward();
		boolean left = in.left();
		boolean right = in.right();

		if (!scramble.isEmpty()) {
			boolean[] permuted = MovementScramble.apply(scramble,
					new boolean[] {forward, backward, left, right});
			forward = permuted[0];
			backward = permuted[1];
			left = permuted[2];
			right = permuted[3];
		}

		this.keyPresses = new Input(forward, backward, left, right,
				// A forced jump ORs with the real key, so it can never cancel a jump
				// the player was already making.
				in.jump() || forceJump, in.shift(), in.sprint());

		// Vanilla's own derivation, reproduced because calculateImpulse is private
		// static. Argument order is (left/right, forward/backward) -- Vec2's x is
		// the strafe axis, y is the forward axis. Getting that pair backwards would
		// silently rotate all movement 90 degrees.
		this.moveVector = new Vec2(
				MovementScramble.impulse(left, right),
				MovementScramble.impulse(forward, backward)).normalized();
	}
}
