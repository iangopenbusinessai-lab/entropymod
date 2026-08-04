package com.entropymod.entropy;

import com.entropymod.EntropyMod;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.util.List;

/**
 * Base class for effects that are nothing more than a permanent attribute
 * modifier. Fourteen of the twenty Tier 1 effects are exactly this, so the
 * idempotency and identity rules live here once rather than being retyped
 * (and eventually mistyped) in each of them.
 *
 * <h2>Why this is safe to re-apply</h2>
 *
 * <p><b>Modifiers are keyed by {@link Identifier}, not UUID, in this Minecraft
 * version</b> — the UUID-based API every older tutorial uses is gone. The id
 * here is derived from the effect id ({@code entropymod:effect/sure_footing}),
 * so it is stable across restarts and unique per effect without a hardcoded
 * UUID table to keep in sync.
 *
 * <p>Application uses {@code addOrUpdateTransientModifier}, which
 * <em>removes any existing modifier with the same id first</em> — verified in
 * its bytecode. That is what makes {@code apply} idempotent: re-applying on
 * every respawn replaces rather than stacks. Using {@code addTransientModifier}
 * instead would add a second copy and double the effect on each rejoin. That is
 * the single most likely bug in this file; do not "simplify" the call.
 *
 * <h2>Why transient and not permanent</h2>
 *
 * <p>{@code addOrReplacePermanentModifier} would write the modifier into the
 * player's own NBT, making the save file a second source of truth alongside
 * {@link AcquiredEffects}. The two could then disagree — a player could carry
 * buffs the mod no longer thinks they have, including after the mod is removed.
 * Transient modifiers are never serialised, so the acquired-effect set is the
 * <b>only</b> truth and attributes are derived state, rebuilt from it on every
 * join and respawn. The cost is that re-application is mandatory rather than a
 * safety net; that is a good trade for an invariant that cannot drift.
 */
public abstract class AttributeEffectBehavior implements EffectBehavior {

	/**
	 * One attribute change. An effect is a list of these.
	 *
	 * @param attribute which attribute
	 * @param amount    the modifier amount, in that operation's units
	 * @param operation see the note on {@code ADD_MULTIPLIED_BASE} vs
	 *                  {@code ADD_MULTIPLIED_TOTAL} in the class javadoc
	 */
	public record Change(Holder<Attribute> attribute, double amount,
						 AttributeModifier.Operation operation) {}

	private final List<Change> changes;

	/** Single-attribute effect -- what most effects are. */
	protected AttributeEffectBehavior(Holder<Attribute> attribute, double amount,
									  AttributeModifier.Operation operation) {
		this(List.of(new Change(attribute, amount, operation)));
	}

	/**
	 * Multi-attribute effect.
	 *
	 * <p>Added when Tier 2 grew effects that are several attributes at once
	 * (Embrace the Moon is gravity + jump + safe-fall; Giant Size is five). The
	 * alternative was for those to implement {@link EffectBehavior} directly and
	 * re-type the idempotency rules -- which is exactly the "fix the abstraction
	 * rather than the caller" case CLAUDE.md calls out. Every existing
	 * single-attribute subclass is untouched.
	 */
	protected AttributeEffectBehavior(List<Change> changes) {
		this.changes = List.copyOf(changes);
	}

	/**
	 * Stable, unique, derived -- no hardcoded UUID table to drift out of sync.
	 *
	 * <p><b>The same id is used for every attribute of a multi-attribute effect,
	 * and that is correct rather than a collision:</b> modifier ids only have to
	 * be unique <em>within one {@code AttributeInstance}</em>, and each attribute
	 * has its own. Giving each change its own id would break nothing but would
	 * make removal and the harness's idempotency check needlessly harder to state.
	 */
	public static Identifier modifierId(String effectId) {
		return EntropyMod.id("effect/" + effectId);
	}

	/** The changes this effect applies. Exposed so the harness can drive them. */
	public List<Change> changes() {
		return changes;
	}

	@Override
	public void apply(EffectContext ctx) {
		for (Change change : changes) {
			AttributeInstance instance = ctx.target().getAttribute(change.attribute());
			if (instance == null) {
				// Only reachable if an attribute isn't present on the player entity type.
				// Log rather than throw: one broken effect must not break the pick loop,
				// and one missing attribute must not skip the effect's other changes.
				EntropyMod.LOGGER.error("Effect '{}' targets an attribute the player does not have "
						+ "-- that change skipped.", ctx.effect().id());
				continue;
			}
			// addOrUpdate, NOT add -- see the class javadoc. This is the idempotency.
			instance.addOrUpdateTransientModifier(new AttributeModifier(
					modifierId(ctx.effect().id()), change.amount(), change.operation()));
		}
		afterApply(ctx);
	}

	@Override
	public void remove(EffectContext ctx) {
		for (Change change : changes) {
			AttributeInstance instance = ctx.target().getAttribute(change.attribute());
			if (instance == null) {
				continue;
			}
			// removeModifier(Identifier) returns false when absent -- safe to call blind.
			instance.removeModifier(modifierId(ctx.effect().id()));
		}
		afterRemove(ctx);
	}

	/** Optional fixup after the modifier lands (e.g. clamping current health). */
	protected void afterApply(EffectContext ctx) {}

	/** Optional fixup after the modifier is taken away. */
	protected void afterRemove(EffectContext ctx) {}
}
