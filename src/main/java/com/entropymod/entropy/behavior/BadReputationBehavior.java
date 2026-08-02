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
 * price back down. Vanilla clamps the final cost to at most a full stack, so this
 * can never make a trade impossible.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}.
 */
public final class BadReputationBehavior extends HookEffectBehavior {

	public static final String ID = "bad_reputation";

	/**
	 * Surcharge as a fraction of the trade's base first-cost, minimum 1 item.
	 * Expressed against the base cost rather than as a flat number so a 1-emerald
	 * trade and a 32-emerald trade are both affected proportionally.
	 */
	public static final float SURCHARGE = 0.25f;
}
