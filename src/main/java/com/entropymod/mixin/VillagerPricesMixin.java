package com.entropymod.mixin;

import com.entropymod.entropy.EffectHooks;
import com.entropymod.entropy.behavior.BadReputationBehavior;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes villagers overcharge for Bad Reputation.
 *
 * <p>Targets {@code Villager.updateSpecialPrices(Player)}, which is vanilla's own
 * per-player pricing pass -- the place reputation (gossip) and Hero of the Village
 * are converted into {@code MerchantOffer.addToSpecialPriceDiff} adjustments.
 * Using the same channel means the surcharge composes with reputation instead of
 * fighting it, and the vanilla trading UI already renders the adjusted price with
 * no extra work.
 *
 * <p><b>Subclass-override risk: structurally impossible.</b> That check is
 * mandatory after the near-miss in CLAUDE.md, and here it is unusually strong --
 * the target is {@code private}, so it cannot be overridden, and there are no
 * subclasses of {@code Villager} in the jar. Note the package moved in this
 * version: {@code net.minecraft.world.entity.npc.villager.Villager}.
 *
 * <p>Injecting at TAIL runs after vanilla's reputation adjustment, so the two are
 * additive on the same field rather than one clobbering the other.
 *
 * <p><b>Players without the effect are unaffected</b> -- the method returns before
 * touching a single offer.
 */
@Mixin(Villager.class)
public abstract class VillagerPricesMixin {

	@Inject(method = "updateSpecialPrices", at = @At("TAIL"))
	private void entropymod$overcharge(Player player, CallbackInfo ci) {
		if (!EffectHooks.hasBadReputation(player)) {
			return;
		}
		Villager villager = (Villager) (Object) this;
		for (MerchantOffer offer : villager.getOffers()) {
			// getCostA(), NOT getBaseCostA(). This runs at TAIL, so getCostA() is
			// already the exact price this player would otherwise have paid --
			// vanilla's formula is
			//   clamp(baseCount + demandAdjustment + specialPriceDiff, 1, maxStackSize)
			// and both demand and the gossip/Hero-of-the-Village adjustment are
			// folded into it by this point. Taking the fraction of that rather than
			// of the raw base is what makes the resulting multiplier hold steady at
			// ~1.6x regardless of demand or existing reputation; against the raw base
			// it drifted, because the denominator the player experiences is not the
			// number the surcharge was computed from.
			int normalPrice = offer.getCostA().getCount();
			int surcharge = Math.max(1,
					Math.round(normalPrice * BadReputationBehavior.SURCHARGE));
			offer.addToSpecialPriceDiff(surcharge);
		}
	}
}
