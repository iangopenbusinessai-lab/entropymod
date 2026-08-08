package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.companion.CompanionSpawner;
import net.minecraft.world.entity.EntityType;

import java.util.List;

/**
 * The Audience (GOOD / COMPANION, Tier 2) -- {@value #AUDIENCE_SIZE} invincible,
 * silent villagers who follow you slowly, at a distance, and do nothing else.
 *
 * <h2>Villagers have NO ownership mechanism. All of this is custom.</h2>
 *
 * <p>Confirmed rather than assumed, and it is the sharpest finding of the
 * companion investigation. {@code Villager} implements
 * {@code VillagerDataHolder} and {@code ReputationEventHandler} and <b>neither
 * {@code TamableAnimal} nor even the broader {@code OwnableEntity} interface</b>.
 * There is no owner field, no tame flag, and nothing ownership-adjacent to build
 * on -- villager "reputation" is per-player gossip about trading, not attachment.
 *
 * <p>So each of the three requirements is built:
 *
 * <table border="1">
 *   <caption>What provides each property</caption>
 *   <tr><th>requirement</th><th>mechanism</th></tr>
 *   <tr><td>follows this specific player</td>
 *       <td>{@code CompanionService} re-issues {@code getNavigation().moveTo(player, speed)};
 *           the owner link is the {@code CompanionRoster} entry, not entity state</td></tr>
 *   <tr><td>invincible</td><td>{@code Entity.setInvulnerable(true)} -- vanilla's own flag</td></tr>
 *   <tr><td>silent</td><td>{@code Entity.setSilent(true)}</td></tr>
 *   <tr><td>persists</td><td><b>free</b> -- {@code Villager.removeWhenFarAway} is
 *           {@code return false}, so villagers never despawn by distance</td></tr>
 * </table>
 *
 * <h2>Why following is a tick service and NOT a Goal -- villagers have no goals</h2>
 *
 * <p>This is the part that would have been got wrong by analogy with the wolves.
 * <b>{@code Villager}'s bytecode contains zero references to
 * {@code goalSelector}</b>: villager movement is entirely brain-driven, through
 * {@code Brain} and {@code MoveToTargetSink}. A custom follow goal added to its
 * goal selector -- which would itself need an accessor mixin, since
 * {@code Mob.goalSelector} is {@code protected final} -- would be silently
 * overridden by the brain on the same tick and would look like a mixin that
 * applied and did nothing. That is failure mode 1 from CLAUDE.md's catalogue,
 * reached by a different route.
 *
 * <p>Re-issuing navigation from a tick service works regardless of which system
 * owns the mob's movement, which is why the same service drives villagers, golems
 * and the llama.
 *
 * <p><b>Known limit, stated rather than discovered later:</b> a villager's brain
 * will still pursue its own errands -- beds, job sites, panicking from zombies --
 * and those compete with the re-issued navigation. The visible result is an
 * audience that drifts and occasionally wanders rather than following in lockstep.
 * For an effect whose entire purpose is atmosphere, drifting hangers-on are an
 * acceptable and arguably better outcome than a rigid conga line, so the brain is
 * left alone rather than disabled with {@code setNoAi}.
 *
 * <h2>Purely cosmetic, and kept that way deliberately</h2>
 *
 * <p>No combat: villagers have no attack goal and none is added, and this effect
 * is not in {@code CompanionService}'s defending set. Trading is not disabled --
 * that would need a mixin on {@code mobInteract} -- but these villagers are
 * spawned unemployed, and an unemployed villager has no trades to offer, so
 * "no trading" falls out of the spawn state rather than needing enforcement.
 *
 * <p>{@link #FOLLOW_START} is deliberately large and {@link #FOLLOW_SPEED}
 * deliberately below a walk: they are meant to loiter at the edge of vision, not
 * crowd the player.
 */
public final class TheAudienceBehavior implements EffectBehavior {

	public static final String ID = "the_audience";

	public static final int AUDIENCE_SIZE = 10;

	/**
	 * The point at which the audience decides it has lost the player and follows.
	 *
	 * <p><b>32 blocks, and it is the project's own established awareness number</b>
	 * -- Danger Sense's radius. It is also about the range at which a villager is
	 * still a resolvable figure rather than a speck, which matters for an effect
	 * whose entire content is being seen. Against the golems' 3-5 hold band it is
	 * <b>more than six times further</b>, which is the "noticeably farther" the
	 * design calls for, and it sits comfortably inside the default 10-chunk
	 * simulation distance so the villagers are still being ticked when it fires.
	 */
	public static final double LOST_TRACK_DISTANCE = 32.0;

	/**
	 * Approach stops here. <b>The audience never comes closer than this, ever</b>,
	 * in any state -- including while frozen, which this rule outranks.
	 *
	 * <p>16 blocks is far enough that they read as background presence rather than
	 * followers, and it is 3x the golems' outer hold so the two effects never
	 * visually merge. It also leaves a 16-block corridor between it and
	 * {@link #LOST_TRACK_DISTANCE}, so an approach is a real journey rather than a
	 * twitch.
	 */
	public static final double MIN_DISTANCE = 16.0;

	/**
	 * An approach continues until the villager is inside this. Hysteresis.
	 *
	 * <p>Without a second threshold a villager sitting at exactly
	 * {@link #LOST_TRACK_DISTANCE} would start approaching, immediately no longer
	 * qualify, stop, drift out and start again -- a visible stutter on the boundary.
	 * Entering at 32 and only leaving inside 20 gives the state a 12-block corridor
	 * to cross, and 20 sits above {@link #MIN_DISTANCE} so the two rules never
	 * contradict.
	 */
	public static final double RESUME_DISTANCE = 20.0;

	/**
	 * Navigation speed multiplier while moving. <b>1.0 is already fast for a
	 * villager and no exotic value is needed.</b>
	 *
	 * <p>A mob's ground acceleration is its {@code MOVEMENT_SPEED} squared
	 * (CLAUDE.md 1e), and a villager's is <b>0.5</b> -- double an iron golem's 0.25.
	 * So at modifier 1.0 a villager accelerates at {@code 0.25} against the golem's
	 * {@code 0.0625}, giving <b>~10.79 blocks/second</b>: 2.5x a walking player and
	 * 1.9x a sprinting one. The previous 0.5 modifier produced 2.70 b/s -- slower
	 * than walking -- which is why they never kept up. Going above 1.0 would be an
	 * unusual speedModifier and would read as teleporting rather than as hurrying.
	 */
	public static final double FOLLOW_SPEED = 1.0;

	@Override
	public void apply(EffectContext ctx) {
		if (!ctx.isFreshPick()) {
			return;
		}
		EntropyManager manager = EntropyManager.get(ctx.server());
		List<String> spawned = CompanionSpawner.spawnGroup(ctx.target(), EntityType.VILLAGER,
				AUDIENCE_SIZE, villager -> {
					villager.setInvulnerable(true);
					villager.setSilent(true);
					// No baby villagers in the audience -- they move differently and read
					// as a different effect.
					villager.setBaby(false);
				});
		manager.recordCompanions(ctx.target(), ID, spawned);
	}

	@Override
	public void remove(EffectContext ctx) {
		// Permanent; nothing calls this.
	}
}
