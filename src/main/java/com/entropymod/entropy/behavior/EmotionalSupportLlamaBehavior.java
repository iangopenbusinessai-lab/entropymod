package com.entropymod.entropy.behavior;

import com.entropymod.entropy.EffectBehavior;
import com.entropymod.entropy.EffectContext;
import com.entropymod.entropy.EntropyManager;
import com.entropymod.entropy.companion.CompanionSpawner;
import com.entropymod.mixin.LlamaStrengthAccessor;
import net.minecraft.world.entity.EntityType;

import java.util.List;

/**
 * Emotional Support Llama (GOOD / COMPANION, Tier 2) -- one invincible, harmless
 * llama that follows you carrying a chest.
 *
 * <h2>Llamas carry chests natively -- confirmed, not assumed</h2>
 *
 * <p>{@code Llama extends AbstractChestedHorse}, which declares
 * {@code hasChest()} / {@code setChest(boolean)} and a synced
 * {@code DATA_ID_CHEST}. The inventory, its screen and its persistence are all
 * ordinary vanilla: {@code AbstractHorse} owns a {@code SimpleContainer},
 * implements {@code HasCustomInventoryScreen}, and saves the contents in the
 * entity's own NBT. <b>None of the storage is built here.</b>
 *
 * <h2>But a llama CANNOT carry a double chest, and the numbers say so</h2>
 *
 * <p>The brief asked for a double-chest inventory. That is not reachable, and the
 * ceiling is arithmetic rather than a tuning choice:
 *
 * <pre>
 *   Llama.getInventoryColumns()                = hasChest() ? getStrength() : 0
 *   AbstractMountInventoryMenu.getInventorySize(columns) = columns * 3
 *   Llama MAX_STRENGTH                         = 5
 * </pre>
 *
 * <p>So the largest llama inventory that exists is <b>5 x 3 = 15 slots</b>,
 * against a double chest's 54 -- <b>27.8% of what was asked for</b>. Reaching 54
 * would mean abandoning the llama container entirely and attaching a custom
 * 54-slot menu to the entity, which would no longer be "a llama carrying a chest"
 * in any vanilla sense and would need its own screen, its own persistence and its
 * own sync.
 *
 * <p><b>Shipped: a real llama at maximum strength, 15 slots.</b> That is the
 * largest genuinely-vanilla answer, and it is flagged rather than quietly
 * delivered as though it were the request.
 *
 * <p>Maximum strength needs one small piece of help: {@code Llama.setStrength} is
 * <b>private</b>, and {@code setRandomStrength} rolls
 * {@code 1 + nextInt(nextFloat() < 0.04 ? 5 : 3)} -- so an ordinary llama is
 * strength 1-3 (3-9 slots) and only reaches 5 about 1.6% of the time.
 * {@link LlamaStrengthAccessor} is an {@code @Invoker} onto that private setter.
 * It is an accessor, not an injection: it adds no behaviour and changes no control
 * flow, which is a materially lower risk class than the mixins CLAUDE.md's
 * catalogue warns about.
 *
 * <h2>Invincibility and chest-carrying are entirely independent</h2>
 *
 * <p>Checked because the brief asked. {@code setInvulnerable} sets a flag on
 * {@code Entity} that {@code hurt}/{@code hurtServer} consult; {@code setChest}
 * sets a synced boolean on {@code AbstractChestedHorse} that gates the inventory's
 * size. They touch no common state, and vanilla never clears one when the other
 * changes. <b>There is no interaction to manage.</b>
 *
 * <p>"Harmless" needs nothing: a llama's only attack is {@code LlamaSpit}, fired
 * by {@code RangedAttackGoal} at a target it acquires through
 * {@code LlamaAttackWolfGoal} and {@code HurtByTargetGoal}. Since it is
 * invulnerable it is never hurt, so it never acquires a target that way, and its
 * only other trigger is wolves. It is not in {@code CompanionService}'s defending
 * set, so nothing here ever gives it a target.
 *
 * <p><b>Tamed on spawn</b> so the player can open the inventory and lead it
 * normally -- {@code AbstractHorse} taming is the temper/owner system, separate
 * from {@code TamableAnimal}, which is why the wolves' free follow-and-defend
 * goals do not apply here and this effect rides {@code CompanionService} instead.
 */
public final class EmotionalSupportLlamaBehavior implements EffectBehavior {

	public static final String ID = "emotional_support_llama";

	/** One llama. */
	public static final int COUNT = 1;

	/** {@code Llama.MAX_STRENGTH}. Five columns of three = 15 slots. */
	public static final int MAX_STRENGTH = 5;

	/** Slots the shipped llama actually has: {@code strength * 3}. */
	public static final int INVENTORY_SLOTS = MAX_STRENGTH * 3;

	/** For contrast in the harness: what was asked for and is not reachable. */
	public static final int DOUBLE_CHEST_SLOTS = 54;

	/**
	 * Closest the llama will stand. Smaller than the golems' because there is only
	 * one of it and it is a pack animal the player will want to reach.
	 */
	public static final double MIN_HOLD = 2.0;

	/** Furthest it drifts before closing in. */
	public static final double MAX_HOLD = 4.0;

	public static final double FOLLOW_SPEED = 1.0;

	@Override
	public void apply(EffectContext ctx) {
		if (!ctx.isFreshPick()) {
			return;
		}
		EntropyManager manager = EntropyManager.get(ctx.server());
		List<String> spawned = CompanionSpawner.spawnGroup(ctx.target(), EntityType.LLAMA,
				COUNT, llama -> {
					// Order matters: strength decides the column count, and the inventory
					// is rebuilt from it when the chest goes on.
					((LlamaStrengthAccessor) llama).entropymod$setStrength(MAX_STRENGTH);
					llama.setChest(true);
					llama.setInvulnerable(true);
					// AbstractHorse's own owner setter, which takes the LivingEntity
					// directly -- NOT TamableAnimal's setOwnerReference, which a llama
					// does not have. The two ownership systems are genuinely separate.
					llama.setTamed(true);
					llama.setOwner(ctx.target());
					llama.setBaby(false);
				});
		manager.recordCompanions(ctx.target(), ID, spawned);
	}

	@Override
	public void remove(EffectContext ctx) {
		// Permanent; nothing calls this. The llama is left alone -- it is carrying the
		// player's items.
	}
}
