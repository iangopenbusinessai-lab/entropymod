package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Slippery Grip (BAD / MOVEMENT, Tier 2) -- you can never sprint again.
 *
 * <h2>The mechanism, and why it beats both obvious alternatives</h2>
 *
 * <p>The hook is {@code LivingEntity.setSprinting(boolean)}, with the argument
 * forced to {@code false}. Everything about that choice came out of bytecode
 * rather than preference:
 *
 * <ul>
 *   <li><b>{@code LivingEntity} overrides {@code setSprinting}, and neither
 *       {@code Player} nor {@code ServerPlayer} overrides it again</b> -- so this
 *       one target is the whole chain for players. The override is also where
 *       vanilla adds and removes {@code SPEED_MODIFIER_SPRINTING}, so forcing the
 *       argument false means vanilla's own code removes the speed bonus; nothing
 *       has to be undone by hand.</li>
 *   <li><b>{@code LocalPlayer} reaches the same method.</b> It calls
 *       {@code this.setSprinting(...)} from four separate places, so the client
 *       never enters the sprinting state either -- which is what makes this
 *       desync-free.</li>
 * </ul>
 *
 * <p><b>Clearing the sprint key in the input record would NOT have worked.</b>
 * That was the first candidate. {@code LocalPlayer} starts a sprint from three
 * distinct triggers -- the sprint key, double-tap-forward, and the sprint toggle
 * -- and the latter two call {@code setSprinting} directly without consulting the
 * key bit at all. Intercepting the key would have blocked one of three paths and
 * looked intermittently broken.
 *
 * <p><b>Force-clearing a flag every tick server-side would have worked but been
 * worse.</b> It leaves a one-tick window each tick in which the player is
 * sprinting, and -- because the client decides sprinting locally and applies its
 * own speed modifier -- it would leave the client believing it is moving faster
 * than the server allows, i.e. rubber-banding. Refusing the transition is
 * strictly cleaner than repairing it afterwards.
 *
 * <h2>Why there are two mixins</h2>
 *
 * <p>{@code EffectHooks} answers "no effect" on the client by design -- the
 * acquired set is server state. So the common mixin covers the server (and is the
 * authority a modified client cannot bypass) and a client-side twin reads
 * {@code ClientRunState}, which {@code ClientEffectsPayload} already populates.
 * <b>Here the client half is load-bearing rather than cosmetic:</b> without it
 * the client would apply a sprint speed modifier the server does not have.
 *
 * <p>Both are {@code @ModifyVariable}, deliberately, so they compose instead of
 * racing -- each independently forces the value to false, and neither cancels the
 * other the way two cancellable {@code @Inject}s would.
 */
public final class SlipperyGripBehavior extends HookEffectBehavior {

	public static final String ID = "slippery_grip";
}
