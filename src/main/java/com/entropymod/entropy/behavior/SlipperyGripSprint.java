package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The one place Slippery Grip's compensating speed modifier is added and
 * removed. Shared by the server mixin, the client mixin and the behavior's own
 * {@code apply}, so all three can never drift apart.
 *
 * <p>See {@link SlipperyGripBehavior} for why a single
 * {@code ADD_MULTIPLIED_TOTAL} modifier both cancels vanilla's sprint bonus and
 * lands the total at half the walking speed.
 *
 * <h2>Two rules this file exists to keep</h2>
 *
 * <ul>
 *   <li><b>{@code addOrUpdateTransientModifier}, never {@code addTransientModifier}.</b>
 *       {@code setSprinting(true)} is called repeatedly with the same value --
 *       vanilla's own line removes and re-adds its modifier every time -- and
 *       {@code addTransientModifier} throws {@code IllegalArgumentException} on a
 *       duplicate id. This is the project-wide idempotency rule, and here it is
 *       load-bearing on the ordinary path rather than only on respawn.</li>
 *   <li><b>The sprint state is read from the entity, not from the caller.</b> At
 *       the tail of {@code setSprinting} the flag is already updated, so
 *       {@code isSprinting()} and the argument agree -- and reading the entity
 *       means {@code apply} can call this with no argument to invent.</li>
 * </ul>
 */
public final class SlipperyGripSprint {

	/** Same {@code entropymod:effect/<id>} scheme every attribute effect uses. */
	private static final Identifier MODIFIER_ID =
			AttributeEffectBehavior.modifierId(SlipperyGripBehavior.ID);

	/** Vanilla's own sprint modifier id -- javap-verified as {@code minecraft:sprinting}. */
	private static final Identifier VANILLA_SPRINT_ID =
			Identifier.withDefaultNamespace(SlipperyGripBehavior.VANILLA_SPRINT_ID_PATH);

	private SlipperyGripSprint() {}

	/**
	 * Brings the compensating modifier in line with the entity's current state.
	 *
	 * <p>Idempotent in both directions: calling it twice with the same arguments
	 * leaves exactly the same modifier set as calling it once, and calling it on an
	 * entity that never had the effect removes nothing that exists.
	 *
	 * @param entity    the entity whose speed is in question
	 * @param hasEffect whether <em>this side</em> believes the run holds Slippery
	 *                  Grip -- the server reads {@code EffectHooks}, the client
	 *                  reads {@code ClientRunState}
	 */
	public static void update(LivingEntity entity, boolean hasEffect) {
		AttributeInstance instance = entity.getAttribute(Attributes.MOVEMENT_SPEED);
		if (instance == null) {
			return;
		}
		if (!hasEffect || !entity.isSprinting()) {
			// Safe to call blind: removeModifier(Identifier) returns false when absent.
			instance.removeModifier(MODIFIER_ID);
			return;
		}
		instance.addOrUpdateTransientModifier(new AttributeModifier(
				MODIFIER_ID, compensatorFor(instance),
				AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
	}

	/**
	 * Reads vanilla's sprint bonus off the instance and derives the compensator
	 * from it, rather than assuming the constant.
	 *
	 * <p>On the ordinary path the modifier is guaranteed present -- vanilla adds it
	 * a few instructions before the tail injection runs -- so the fallback is for
	 * the case where a future version or another mod has changed how sprinting is
	 * applied. Falling back keeps the curse roughly right instead of silently
	 * turning it into a flat -50%.
	 */
	private static double compensatorFor(AttributeInstance instance) {
		AttributeModifier vanilla = instance.getModifier(VANILLA_SPRINT_ID);
		double sprintAmount = vanilla != null
				? vanilla.amount()
				: SlipperyGripBehavior.VANILLA_SPRINT_AMOUNT;
		return SlipperyGripBehavior.compensatorAmount(sprintAmount);
	}
}
