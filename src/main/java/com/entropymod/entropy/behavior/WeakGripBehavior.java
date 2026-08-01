package com.entropymod.entropy.behavior;

import com.entropymod.entropy.AttributeEffectBehavior;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Weak Grip (BAD / COMBAT) -- -2 attack damage.
 *
 * <p>ATTACK_DAMAGE floors at 0.0 and the player's base is only 1.0, so BARE-HANDED attacks are reduced to 0 damage by this effect. Any weapon adds its own modifier and stays comfortably positive, so this is survivable (counterplay: carry a weapon) -- but the punchless-fist case is a real, intended consequence, not an oversight.
 */
public final class WeakGripBehavior extends AttributeEffectBehavior {

	public static final String ID = "weak_grip";

	public WeakGripBehavior() {
		super(Attributes.ATTACK_DAMAGE, -2.0, AttributeModifier.Operation.ADD_VALUE);
	}
}
