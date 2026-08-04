package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Crouch Invincibility (GOOD / SURVIVAL, Tier 2) -- while sneaking, nothing can
 * hurt you.
 *
 * <p>No cooldown, no charges, no damage-type exceptions: every source is zeroed
 * for as long as the player is actually crouching at the instant of the hit.
 * The cost is entirely positional -- sneaking is slow, cannot jump usefully, and
 * is a poor way to fight -- so the counterplay is that using it means not doing
 * anything else.
 *
 * <h2>Gated on the real crouch state at the moment of the hit</h2>
 *
 * <p>{@code Entity.isCrouching()} is vanilla's own pose-derived crouch state,
 * which is what the player sees on screen -- not {@code isShiftKeyDown()}, the
 * raw input flag. They differ: a player who is shifting but forced upright, or
 * crouching without holding the key (a 1-block gap), would otherwise get an
 * answer that disagrees with their own model of what is happening.
 *
 * <p>The check happens inside the damage hook, so it is evaluated per hit rather
 * than latched. Standing up mid-fight ends the protection on the very next blow;
 * this is asserted in the harness because "invincible while sneaking" and
 * "invincible once you have sneaked" are easy to conflate in an implementation
 * and impossible to tell apart from a single in-game test.
 *
 * <h2>Interaction with Flamboyant, which is deliberate</h2>
 *
 * <p>A run holding both is protected from the fire death while crouching. That
 * falls out of ordering rather than a special case: the invincibility check
 * cancels the damage outright before Flamboyant's amplification can reach a
 * player who is going to take zero anyway. It is the coherent reading of "zero
 * damage from any source" and is asserted so it cannot silently invert.
 */
public final class CrouchInvincibilityBehavior extends HookEffectBehavior {

	public static final String ID = "crouch_invincibility";
}
