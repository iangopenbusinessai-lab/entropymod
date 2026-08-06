package com.entropymod.entropy.behavior;

import com.entropymod.EntropyMod;
import com.entropymod.entropy.HookEffectBehavior;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Ore Sense (GOOD / UTILITY, Tier 2) -- ores within {@value #RADIUS} blocks are
 * outlined through terrain.
 *
 * <h2>There is no {@code #minecraft:ores} tag, so this ships its own</h2>
 *
 * <p>Verified rather than assumed: {@code BlockTags} declares eight per-material
 * ore tags -- {@code COAL_ORES}, {@code IRON_ORES}, {@code COPPER_ORES},
 * {@code GOLD_ORES}, {@code REDSTONE_ORES}, {@code LAPIS_ORES},
 * {@code DIAMOND_ORES}, {@code EMERALD_ORES} -- and <b>no union tag over them</b>.
 *
 * <p><b>Ancient debris and nether quartz ore are in none of the eight</b>, and
 * they are <b>included deliberately</b> rather than dropped. An "ore sense" that
 * stayed silent on the single most valuable block in the game would read as a bug,
 * not as a scope decision. (Note {@code GOLD_ORES} <em>does</em> already contain
 * nether gold ore, so that one needs no special handling.)
 *
 * <p>So the effect is scoped by a mod-supplied block tag,
 * {@code entropymod:ore_sense_targets}, whose JSON is the eight vanilla tags plus
 * those two blocks. Three reasons this beats a hardcoded list, all precedents in
 * this project:
 *
 * <ul>
 *   <li>A modded or datapack ore that joins any vanilla ore tag is picked up for
 *       free -- the same argument that made Clumsy Digger use
 *       {@code #minecraft:enchantable/mining}.</li>
 *   <li>Tag merging is additive, so a pack can extend the set but nothing can
 *       narrow it out from under the effect.</li>
 *   <li>The contents are inspectable as data, and the harness reads the shipped
 *       JSON rather than restating the constant.</li>
 * </ul>
 *
 * <h2>Cost -- this one IS a volume scan, unlike Danger Sense</h2>
 *
 * <p>Blocks are not entities, so there is no section index to query and the cost
 * really is proportional to the volume. An {@value #RADIUS}-block radius is a
 * 17x17x17 box = <b>4,913 positions</b> -- deliberately the same number as Green
 * Thumb's rescan, which is the known-safe size in this project.
 *
 * <p>It is also <b>entirely client-side</b>. The client already holds these
 * blocks in its own {@code ClientLevel}, so there is no server involvement and no
 * payload beyond the {@code ClientEffectsPayload} bit that already says the run
 * holds the effect.
 *
 * <ul>
 *   <li><b>Rescan: O(4,913) block-state reads every
 *       {@value #RESCAN_INTERVAL_TICKS} ticks</b>, amortising to ~246 reads per
 *       tick -- identical to Green Thumb, on the client instead of the server.</li>
 *   <li><b>Render: O(ores found) per frame, from the cache.</b> Never a scan per
 *       frame; at 60fps that would be 295,000 block reads a second.</li>
 *   <li>Nothing runs at all when the run does not hold the effect.</li>
 * </ul>
 *
 * <p><b>The sphere-vs-box correction applies here exactly as it does to Danger
 * Sense.</b> A bare 17-cube reaches {@code 8 * sqrt(3) = 13.86} blocks at its
 * corners -- a 73% over-range. The scan filters by squared distance, and the
 * harness asserts the boundary in both directions and at the corner distance
 * specifically.
 */
public final class OreSenseBehavior extends HookEffectBehavior {

	public static final String ID = "ore_sense";

	/** Radius in blocks. */
	public static final double RADIUS = 8.0;

	/** Precomputed; the filter runs once per candidate position per rescan. */
	public static final double RADIUS_SQR = RADIUS * RADIUS;

	/**
	 * Half-width of the scanned box, in whole blocks. {@code ceil(RADIUS)}, so the
	 * box is 17x17x17 -- every position that could be inside the sphere, and no
	 * more rows than that.
	 */
	public static final int REACH = (int) Math.ceil(RADIUS);

	/** How often the cache is rebuilt. Same cadence as Green Thumb's rescan. */
	public static final int RESCAN_INTERVAL_TICKS = 20;

	/**
	 * The block set. Supplied by this mod as data rather than code -- see the
	 * class javadoc for why, and {@code data/entropymod/tags/block/ore_sense_targets.json}
	 * for the contents.
	 */
	public static final TagKey<Block> ORE_TARGETS =
			TagKey.create(Registries.BLOCK, EntropyMod.id("ore_sense_targets"));

	/**
	 * Whether a squared distance is inside the radius. Free of Minecraft types so
	 * the harness can drive the boundary directly.
	 */
	public static boolean inRange(double distanceSqr) {
		return distanceSqr <= RADIUS_SQR;
	}
}
