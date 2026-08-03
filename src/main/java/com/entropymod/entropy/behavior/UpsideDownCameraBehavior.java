package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Upside-Down Camera (BAD / DEBUFF, Tier 2) -- the view is permanently rolled
 * 180 degrees.
 *
 * <p><b>Playability was the gating question and it resolves to "hard but
 * functional".</b> The mechanism is a pure roll about the view axis through
 * vanilla's own camera quaternion -- see {@code CameraMixin}, which carries the
 * bytecode evidence. The decisive fact is that the camera's forward vector is
 * unchanged by a roll, so <b>the crosshair still points at exactly the same
 * block</b>: mining, attacking and item use are unaffected, and the HUD (screen
 * space) stays upright and readable. What changes is the presented orientation
 * and the feel of the mouse, both axes reading as inverted.
 *
 * <p>Counterplay: none mechanical, and none is intended -- it is a Tier 2 curse.
 * What it is not is a soft-lock: nothing becomes unreachable or untargetable.
 *
 * <p><b>If in-game testing shows this is genuinely sickening rather than merely
 * disorienting</b>, the revert is one constant -- {@code CameraMixin.ROLL_RADIANS}
 * to {@code 0.0f} -- and no other code changes. Recorded here because the effect
 * is permanent with no removal mechanism, so the escape hatch needs to be
 * obvious.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 * Membership in the acquired set, mirrored to the client, is the whole effect.
 */
public final class UpsideDownCameraBehavior extends HookEffectBehavior {

	public static final String ID = "upside_down_camera";
}
