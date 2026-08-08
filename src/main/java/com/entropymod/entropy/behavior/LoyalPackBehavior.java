package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.companion.CompanionSpawner;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Loyal Pack (GOOD / COMPANION, Tier 2) -- {@value #PACK_SIZE} permanently tamed,
 * armoured wolves that follow and defend you forever.
 *
 * <h2>Almost all of this is FREE, and that was the investigation's main finding</h2>
 *
 * <p>{@code Wolf.registerGoals()} registers, unconditionally and regardless of
 * taming state:
 *
 * <ul>
 *   <li>{@code FollowOwnerGoal} -- following, including the teleport-to-owner
 *       behaviour when the path is too long;</li>
 *   <li>{@code OwnerHurtByTargetGoal} -- attack whatever attacked the owner;</li>
 *   <li>{@code OwnerHurtTargetGoal} -- attack whatever the owner attacked.</li>
 * </ul>
 *
 * <p>All three gate themselves on the owner, so <b>a single
 * {@code TamableAnimal.tame(player)} call turns the entire "follow and defend"
 * specification on</b>. No custom goal, no mixin, and no entry in
 * {@code CompanionService} -- which deliberately excludes this effect, because
 * running a mod-side follow loop alongside vanilla's would mean two systems
 * writing the same navigation every tick.
 *
 * <p>Despawn immunity is free too, and for a broader reason than taming:
 * <b>{@code Animal.removeWhenFarAway(double)} is {@code return false}</b> --
 * literally {@code iconst_0; ireturn}. Every animal in the game is despawn-immune,
 * tamed or not, so no {@code setPersistenceRequired()} call is needed and adding
 * one would be cargo cult. Saving across reload is ordinary entity persistence:
 * the wolves are written with their chunks like any other entity, with the tamed
 * flag and owner in their own NBT. <b>Nothing mod-side persists them and nothing
 * mod-side can lose them</b> -- the same property Blight Touched's dead bushes
 * have.
 *
 * <h2>Wolf armour is a real item and a real slot</h2>
 *
 * <p>{@code Items.WOLF_ARMOR} exists and goes in {@code EquipmentSlot.BODY} --
 * the slot vanilla added for exactly this. It is applied with the ordinary
 * {@code setItemSlot}, so it renders, dyes, absorbs damage and drops on death
 * exactly like armour a player fitted by hand.
 *
 * <h2>Fifteen entities: the cost, stated so it is inspectable</h2>
 *
 * <p><b>This is the largest single entity commitment in the project and it is
 * worth flagging honestly.</b>
 *
 * <ul>
 *   <li><b>Pathfinding is the real cost, not existence.</b> Fifteen
 *       {@code PathfinderMob}s each run {@code FollowOwnerGoal}, which re-paths on
 *       a reduced-tick cadence, and a path search is orders of magnitude more
 *       expensive than the tick of an idle entity. Vanilla's own mob cap for the
 *       {@code CREATURE} category is 10 per player-area, so a fifteen-wolf pack is
 *       genuinely above what the game budgets for one player's animals.</li>
 *   <li><b>It is bounded and one-shot.</b> Nothing re-summons: the pack is spawned
 *       once and never topped up (see {@code CompanionRoster}), so the count can
 *       only ever fall. A player cannot accumulate packs by relogging.</li>
 *   <li><b>The pathological case is a confined space.</b> Fifteen wolves in a 3x3
 *       room push each other constantly and each re-path every few ticks. That is
 *       a real frame-rate cost on a modest machine, and it is inherent to the
 *       effect rather than to this implementation.</li>
 *   <li>They also fight. Fifteen wolves engaging a raid is fifteen attack goals
 *       plus knockback and particles -- the heaviest moment this effect creates.</li>
 * </ul>
 *
 * <p><b>Recommendation, not a silent change:</b> the specified 15 ships, and
 * {@link #PACK_SIZE} is one constant if it reads as too heavy in play. 8 would sit
 * within vanilla's own creature budget while still being unmistakably a pack.
 *
 * <p>Unlike every other effect in this project, {@code apply} is <b>not</b>
 * idempotent by rebuilding derived state -- it spawns world state, so it is gated
 * on {@link EffectContext#isFreshPick()}. See {@code CompanionRoster} for why that
 * gate, rather than a liveness lookup, is the guarantee.
 */
public final class LoyalPackBehavior implements EffectBehavior {

	public static final String ID = "loyal_pack";

	/** As specified. See the cost note in the class javadoc before raising it. */
	public static final int PACK_SIZE = 15;

	@Override
	public void apply(EffectContext ctx) {
		// The one-shot gate. apply() runs again on every respawn, rejoin and
		// dimension change; without this that is 15 more wolves each time.
		if (!ctx.isFreshPick()) {
			return;
		}
		EntropyManager manager = EntropyManager.get(ctx.server());
		List<String> spawned = CompanionSpawner.spawnGroup(ctx.target(), EntityType.WOLF,
				PACK_SIZE, wolf -> {
					// The single call that turns on following AND both defend goals.
					wolf.tame(ctx.target());
					wolf.setItemSlot(EquipmentSlot.BODY, new ItemStack(Items.WOLF_ARMOR));
					// Sitting is the one tamed-wolf state that would defeat the effect.
					wolf.setOrderedToSit(false);
				});
		manager.recordCompanions(ctx.target(), ID, spawned);
	}

	@Override
	public void remove(EffectContext ctx) {
		// Effects are permanent; nothing calls this. Wolves are deliberately NOT
		// despawned here -- they are real, tamed, mortal animals the player now owns,
		// and deleting someone's pets is not something a removal path should do
		// quietly.
	}
}
