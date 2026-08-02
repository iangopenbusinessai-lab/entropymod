package com.entropymod.entropy;

import com.entropymod.EntropyMod;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;

/**
 * Attributes this mod registers itself, because vanilla has none that fit.
 *
 * <p>Right now that is exactly one: item pickup range. The movement/physics
 * session established by bytecode that <b>no vanilla attribute governs item
 * pickup</b> — {@code ENTITY_INTERACTION_RANGE} / {@code BLOCK_INTERACTION_RANGE}
 * feed only {@code Player.entityInteractionRange()} / {@code blockInteractionRange()},
 * which are attack and use <em>reach</em>, a different mechanic. Pickup is
 * hardcoded in {@code Player.aiStep()} as
 * {@code getBoundingBox().inflate(1.0, 0.5, 1.0)}. See CLAUDE.md.
 *
 * <p><b>A custom attribute is inert on its own.</b> Nothing in vanilla reads this
 * one, so it does nothing until {@code PlayerItemPickupMixin} multiplies the
 * inflation by it. The attribute is not a way to avoid a mixin — it is a way to
 * give the mixin a value to read that the rest of the mod can manipulate through
 * the ordinary {@link AttributeEffectBehavior} path, so Magnetic Boots gets
 * idempotency, respawn/rejoin survival and removal semantics for free rather than
 * needing a bespoke code path.
 *
 * <h2>Why it is a multiplier with default 1.0</h2>
 *
 * <p>The vanilla inflation is not a single number — it is {@code (1.0, 0.5, 1.0)},
 * a wider-than-tall box. Storing an absolute radius would have to pick one of
 * those and would silently change the box's shape. A dimensionless multiplier
 * scales all three and keeps the vanilla proportions, and its default of
 * {@code 1.0} means <b>a player with no effect gets exactly vanilla behaviour by
 * construction</b> rather than by the mixin remembering to check.
 *
 * <p>Registered {@code syncable} so the client's copy of {@code aiStep} agrees
 * with the server's. Pickup is server-authoritative either way, but a disagreeing
 * client would iterate a different entity set each tick for no benefit.
 */
public final class EntropyAttributes {

	/** Registry id of {@link #PICKUP_RANGE}. Safe to read at any time, unlike the holder's value. */
	public static final Identifier PICKUP_RANGE_ID = EntropyMod.id("pickup_range");

	/** Multiplier on the vanilla item/XP pickup box. 1.0 = vanilla. */
	public static final Holder<Attribute> PICKUP_RANGE = Registry.registerForHolder(
			BuiltInRegistries.ATTRIBUTE,
			PICKUP_RANGE_ID,
			new RangedAttribute("attribute.name.entropymod.pickup_range", 1.0, 0.0, 16.0)
					.setSyncable(true));

	/**
	 * Forces class-init so the static registration above runs. Called from
	 * {@code EntropyMod.onInitialize}.
	 *
	 * <p><b>Do not dereference the holder here.</b> {@code registerForHolder}
	 * returns a {@code Holder.Reference} whose value is not bound until the
	 * registry freezes, and Fabric deliberately delays that freeze until after mod
	 * init so mods can register at all (its registry-sync {@code BootstrapMixin}
	 * has a method named {@code delayRegistryFreeze} for exactly this). Calling
	 * {@code PICKUP_RANGE.value()} at this point therefore throws
	 * {@code IllegalStateException: Trying to access unbound value} and takes mod
	 * initialization down with it. Log the key, which is available immediately.
	 *
	 * <p>This is not hypothetical -- the first version of this method logged
	 * {@code PICKUP_RANGE.value()} and the headless harness caught it as a hard
	 * crash. Anything that needs the {@code Attribute} itself must wait until the
	 * game is running; by the time an {@link EffectBehavior} applies a modifier,
	 * the registry is long frozen and the holder is bound.
	 */
	public static void register() {
		EntropyMod.LOGGER.info("Registered custom attribute: {}", PICKUP_RANGE_ID);
	}

	private EntropyAttributes() {}
}
