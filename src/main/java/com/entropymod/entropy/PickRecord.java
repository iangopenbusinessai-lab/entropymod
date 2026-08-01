package com.entropymod.entropy;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One entry in the run's pick history -- what was chosen, when, and at what
 * entropy. Append-only; nothing rewrites a record once made.
 *
 * <p>Stores the effect's name and description as strings rather than an id
 * reference on purpose: history is a record of what the player actually saw, so
 * it must survive an effect being retuned or removed from {@link EffectRegistry}
 * in a later version. That matters more now that history is persisted to disk
 * and can outlive the build that wrote it.
 *
 * @param pickNumber        1-based, in the order picks were made
 * @param phase             GOOD or BAD -- redundant with pickNumber's parity today,
 *                          but stored explicitly so history stays readable if the
 *                          strict alternation rule is ever relaxed
 * @param effectId          the chosen effect's id
 * @param effectName        display name as shown in the GUI at the time
 * @param effectDescription description as shown in the GUI at the time
 * @param entropyAtPick     entropy BEFORE this pick's +1 was applied -- i.e. the
 *                          number the player was looking at when they chose
 */
public record PickRecord(
		int pickNumber,
		EffectPhase phase,
		String effectId,
		String effectName,
		String effectDescription,
		int entropyAtPick
) {
	/**
	 * Phase is stored by NAME, not ordinal, so reordering the enum can't silently
	 * rewrite old saves. Unknown names fall back to GOOD rather than failing the
	 * whole world load -- a wrong label on a history row is a far smaller problem
	 * than an unloadable save.
	 */
	private static final Codec<EffectPhase> PHASE_CODEC = Codec.STRING.xmap(
			name -> {
				try {
					return EffectPhase.valueOf(name);
				} catch (IllegalArgumentException e) {
					return EffectPhase.GOOD;
				}
			},
			EffectPhase::name);

	public static final Codec<PickRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("pick_number").forGetter(PickRecord::pickNumber),
			PHASE_CODEC.fieldOf("phase").forGetter(PickRecord::phase),
			Codec.STRING.fieldOf("effect_id").forGetter(PickRecord::effectId),
			Codec.STRING.fieldOf("effect_name").forGetter(PickRecord::effectName),
			Codec.STRING.fieldOf("effect_description").forGetter(PickRecord::effectDescription),
			Codec.INT.fieldOf("entropy_at_pick").forGetter(PickRecord::entropyAtPick)
	).apply(instance, PickRecord::new));
}
