package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Bad Reputation (BAD / DEBUFF) -- villagers charge you more.
 *
 * <p><b>Fabric API has no trade-price event in this version</b> -- it has no
 * trade-related classes at all -- so this is mixin-only. See CLAUDE.md for the
 * investigation.
 *
 * <p>The hook is {@code Villager.updateSpecialPrices(Player)}, which is
 * <b>vanilla's own per-player pricing mechanism</b>: it is where reputation
 * (gossip) and Hero of the Village are turned into price adjustments, via
 * {@code MerchantOffer.addToSpecialPriceDiff}. Using the same channel means this
 * effect stacks with reputation rather than fighting it, and it is displayed to
 * the player by the normal strikethrough-price UI with no extra work.
 *
 * <p><b>Subclass-override risk: structurally impossible here.</b> That check is
 * mandatory after the near-miss recorded in CLAUDE.md, and it comes out clean for
 * an unusually strong reason -- {@code updateSpecialPrices} is {@code private},
 * so it cannot be overridden at all, and independently there are no subclasses of
 * {@code Villager} in the jar. Note the class moved package in this version: it
 * is {@code net.minecraft.world.entity.npc.villager.Villager}, not
 * {@code net.minecraft.world.entity.npc.Villager}.
 *
 * <p><b>Scope:</b> wandering traders are unaffected. {@code WanderingTrader}
 * extends {@code AbstractVillager}, not {@code Villager}, and has no special-price
 * mechanism to hook -- so it is out of reach rather than overlooked.
 *
 * <p>Counterplay: the surcharge is additive on the same value good reputation
 * reduces, so trading repeatedly (or curing a zombie villager) still claws the
 * price back down -- and because the surcharge is now a fraction of the
 * <em>already-discounted</em> price, good reputation is worth <b>more</b> under
 * this curse than it was under the old flat-from-base version, not less. Vanilla
 * clamps the final cost to at most a full stack, so this can never make a trade
 * impossible.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 */
public final class BadReputationBehavior extends HookEffectBehavior {

	public static final String ID = "bad_reputation";

	/**
	 * Surcharge as a fraction of the price the player would otherwise pay,
	 * minimum 1 item. Retuned from 0.25 after measuring what it actually produced.
	 *
	 * <p><b>The basis matters as much as the number.</b> This is a fraction of
	 * {@code getCostA()} -- the offer's <em>current</em> cost, demand and gossip
	 * already folded in -- not of {@code getBaseCostA()}. Vanilla's price is
	 * {@code clamp(base + demandAdjustment + specialPriceDiff, 1, maxStackSize)},
	 * and {@code specialPriceDiff} is a flat integer, so this effect is an additive
	 * surcharge that only behaves like a multiplier if it is computed against the
	 * same denominator the player experiences.
	 *
	 * <p>At 0.65 the resulting final price is <b>1.50x-1.75x</b> across every
	 * realistic trade cost, which is the target. Two boundaries worth knowing:
	 *
	 * <ul>
	 *   <li><b>A 1-emerald trade becomes 2 emeralds, i.e. 2.00x.</b> Unavoidable
	 *       rather than a tuning miss -- prices are integers, so the smallest
	 *       possible increase on a 1-emerald trade <em>is</em> +100%.</li>
	 *   <li><b>Above ~39 emeralds the surcharge is eaten by vanilla's own clamp</b>
	 *       to a single stack, and a trade already costing 64 is unaffected. That is
	 *       vanilla's hard ceiling on trade cost, not something this can exceed.</li>
	 * </ul>
	 */
	public static final float SURCHARGE = 0.65f;
}
