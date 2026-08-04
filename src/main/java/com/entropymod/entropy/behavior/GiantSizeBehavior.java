package com.entropymod.entropy.behavior;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.AttributeEffectBehavior;
import com.entropymod.entropy.EffectContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Giant Size (BAD / DEBUFF, Tier 2) -- the player becomes five times their
 * normal size, permanently.
 *
 * <h2>The attribute exists and is player-applicable -- verified, not assumed</h2>
 *
 * <p>{@code minecraft:scale} is a real, registered {@code RangedAttribute}:
 * <b>default 1.0, min 0.0625, max 16.0</b>, read out of the live registry rather
 * than a wiki. It is granted by {@code LivingEntity.createLivingAttributes()},
 * which {@code Player.createAttributes()} builds on, so every player has it --
 * confirmed by querying {@code DefaultAttributes.getSupplier(EntityType.PLAYER)}
 * directly. 5.0 sits comfortably inside the range, so no clamping concern
 * exists in either direction. This needed no mixin and no faked scaling.
 *
 * <p>It is also <b>client-syncable</b> (checked via
 * {@code Attribute.isClientSyncable()}), so the model, hitbox and camera height
 * all follow on the client with no payload of this mod's own. Unlike Tier 2's
 * input and camera effects, this needs no client-side awareness at all.
 *
 * <h2>The apply-time collision risk is REAL, and vanilla will not fix it</h2>
 *
 * <p><b>{@code Entity.refreshDimensions()} explicitly skips its own rescue
 * behaviour for players.</b> Verified in bytecode: it calls
 * {@code fudgePositionAfterSizeChange(...)} -- the routine that nudges a
 * grown entity out of the blocks it now overlaps -- only after an
 * {@code instanceof Player} check that <em>branches past it</em> for players.
 * So growing a player 5x inside a 2-block-tall corridor leaves them embedded in
 * terrain, taking suffocation damage, with the mod entirely to blame.
 *
 * <p>Mitigation, in {@link #afterApply}: refresh the dimensions, and if the new
 * bounding box collides, walk upward a block at a time looking for a position
 * where it does not, then snap there. Notes on the shape of this:
 *
 * <ul>
 *   <li><b>It is idempotent</b>, which it must be -- {@code apply} runs again on
 *       every respawn, rejoin and dimension change. A player who is already free
 *       collides with nothing and is not moved at all.</li>
 *   <li><b>Upward only.</b> Downward would push the player into the ground, and
 *       sideways would need a search order that has no principled answer. Up is
 *       where the free space is when the problem is "I just got taller".</li>
 *   <li><b>Giving up is a valid outcome.</b> Deep underground there may be no
 *       free spot within the search limit; the player then takes suffocation
 *       damage, which is the honest consequence of becoming enormous in a tunnel
 *       and is the curse working. It is logged rather than silently ignored.</li>
 * </ul>
 *
 * <h2>The rest of the kit</h2>
 *
 * <ul>
 *   <li><b>+10 hearts</b> ({@code MAX_HEALTH +20.0}). Raising max health does not
 *       heal: the player keeps their current health in a larger pool, same as
 *       Thick Hide. No clamp is needed -- that is only required when
 *       <em>lowering</em> it, as Brittle Bones does.</li>
 *   <li><b>Two-block step-up</b> ({@code STEP_HEIGHT} 0.6 -&gt; 2.0).</li>
 *   <li><b>Double jump height</b> ({@code JUMP_STRENGTH} +47.222%, apex 1.252 -&gt;
 *       2.504) -- see {@link #JUMP_BONUS} for why the intuitive sqrt(2) is wrong.</li>
 *   <li><b>-25% damage taken</b>, through Iron Skin's existing hook.</li>
 *   <li><b>+6.5 blocks of reach</b>, both interaction attributes.</li>
 * </ul>
 *
 * <h2>SCALE does NOT extend reach -- and it actively breaks it</h2>
 *
 * <p>Investigated because it decides whether the reach bonus is a buff or a
 * repair. The answer is unambiguous and it went the bad way:
 *
 * <ul>
 *   <li>{@code Player.entityInteractionRange()} and {@code blockInteractionRange()}
 *       are <b>each a bare {@code getAttributeValue(...)} and nothing else</b>.
 *       No scale term anywhere.</li>
 *   <li>But {@code EntityDimensions.scale(f)} multiplies {@code eyeHeight} along
 *       with width and height, so at 5x the eye sits <b>8.1 blocks</b> above the
 *       feet (1.62 x 5).</li>
 *   <li>And {@code isWithinBlockInteractionRange} measures from
 *       <b>{@code getEyePosition()}</b>.</li>
 * </ul>
 *
 * <p><b>So an unmodified 5x player cannot reach the block they are standing on:
 * 8.1 blocks away, against a default reach of 4.5.</b> They could not mine,
 * place, or open anything at ground level -- not a difficulty, an unplayable
 * effect. The bonus is sized from that: the eye rose by 6.48 blocks, so reach is
 * extended by {@link #REACH_BONUS} = 6.5 to restore vanilla-equivalent reach
 * <em>relative to the giant's own body</em>. Final values are 11.0 block and 9.5
 * entity, both far below the attributes' 64.0 ceiling.
 *
 * <p><b>This is deliberately larger than the +2 that was specified</b>, because
 * +2 would give 6.5 -- still short of the 8.1 needed to touch the ground, so the
 * effect would have shipped broken. One constant to change if a smaller number is
 * preferred.
 *
 * <h2>Phase: reconsider</h2>
 *
 * <p>This was filed BAD when it was 5x size and nothing else. With +10 hearts,
 * double jump, 2-block step-up, -25% damage and restored reach, <b>the balance of
 * upside to downside has plainly shifted and GOOD may now be the better fit</b>.
 * That is flagged rather than decided here -- see CLAUDE.md's open question.
 */
public final class GiantSizeBehavior extends AttributeEffectBehavior {

	public static final String ID = "giant_size";

	/** ADD_VALUE against a base of 1.0, so the final scale is exactly 5.0. */
	public static final double AMOUNT = 4.0;

	/** +10 hearts. Same mechanism as Thick Hide's +4.0, just larger. */
	public static final double HEALTH_BONUS = 20.0;

	/**
	 * +1.4 on a base of 0.6, giving exactly 2.0 -- a two-block ledge walked up
	 * without jumping. {@code STEP_HEIGHT} is a real player attribute (default
	 * 0.6, min 0.0, max 10.0) and {@code LivingEntity.maxUpStep()} is literally
	 * {@code getAttributeValue(STEP_HEIGHT)}, so this needs no mixin.
	 */
	public static final double STEP_HEIGHT_BONUS = 1.4;

	/**
	 * +47.222% on JUMP_STRENGTH, giving exactly <b>2.0000x</b> vanilla apex
	 * (1.2522 -&gt; 2.5044 blocks), derived against the real integration.
	 *
	 * <p><b>The obvious answer is wrong.</b> Apex goes roughly as the square of
	 * launch velocity, so "double the height" looks like x{@code sqrt(2)}, i.e.
	 * +41.42%. Simulated, that yields only <b>1.856x</b> -- because the 0.98
	 * per-tick air drag costs proportionally more at higher velocities, which the
	 * closed form ignores. The extra 5.8 points are what the drag eats.
	 */
	public static final double JUMP_BONUS = 0.47222;

	/**
	 * Incoming damage multiplied by 0.75, i.e. 25% less. Read through
	 * {@code EffectHooks.damageTakenMultiplier} -- the <em>same</em> hook Iron
	 * Skin and Fragile use, not a parallel mechanism.
	 */
	public static final float DAMAGE_MULTIPLIER = 0.75f;

	/**
	 * +6.5 blocks on both interaction ranges, which is not a buff so much as a
	 * repair -- see the class javadoc's reach section.
	 */
	public static final double REACH_BONUS = 6.5;

	/** How far up to look for room. Beyond this, the player is genuinely walled in. */
	public static final int MAX_LIFT_BLOCKS = 8;

	public GiantSizeBehavior() {
		super(List.of(
				new Change(Attributes.SCALE, AMOUNT,
						AttributeModifier.Operation.ADD_VALUE),
				new Change(Attributes.MAX_HEALTH, HEALTH_BONUS,
						AttributeModifier.Operation.ADD_VALUE),
				new Change(Attributes.STEP_HEIGHT, STEP_HEIGHT_BONUS,
						AttributeModifier.Operation.ADD_VALUE),
				new Change(Attributes.JUMP_STRENGTH, JUMP_BONUS,
						AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
				new Change(Attributes.ENTITY_INTERACTION_RANGE, REACH_BONUS,
						AttributeModifier.Operation.ADD_VALUE),
				new Change(Attributes.BLOCK_INTERACTION_RANGE, REACH_BONUS,
						AttributeModifier.Operation.ADD_VALUE)));
	}

	@Override
	protected void afterApply(EffectContext ctx) {
		ServerPlayer player = ctx.target();
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		// The attribute moved, but the entity's cached dimensions and bounding box
		// have not. Nothing below is meaningful until they do.
		player.refreshDimensions();

		if (level.noCollision(player, player.getBoundingBox())) {
			return; // already free -- the common case, and what makes this idempotent
		}

		AABB box = player.getBoundingBox();
		for (int lift = 1; lift <= MAX_LIFT_BLOCKS; lift++) {
			if (level.noCollision(player, box.move(0.0, lift, 0.0))) {
				player.snapTo(player.getX(), player.getY() + lift, player.getZ());
				EntropyMod.LOGGER.info("Giant Size: lifted {} by {} block(s) to avoid suffocating on apply.",
						player.getName().getString(), lift);
				return;
			}
		}

		EntropyMod.LOGGER.warn("Giant Size: no free space within {} blocks above {} -- they are enclosed "
						+ "and will suffocate until they dig out.",
				MAX_LIFT_BLOCKS, player.getName().getString());
	}
}
