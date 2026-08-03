package com.entropymod.entropy.behavior;

import com.entropymod.entropy.HookEffectBehavior;

/**
 * Blight Touched (BAD / UTILITY) -- every crop you walk through withers into a
 * dead bush.
 *
 * <h2>This effect no longer uses the growth-speed multiplier hook</h2>
 *
 * <p>It used to halve {@code CropBlock.getGrowthSpeed} for crops within
 * {@link GreenThumbBehavior#RADIUS} blocks. That mechanic has been retired, and
 * because Blight Touched was the last effect on that hook, the hook itself and
 * its mixin were <b>deleted</b> rather than left in place returning 1.0 -- the
 * same discipline Green Thumb's retirement followed. A neutral multiplier can be
 * quietly re-wired and double-applied on top of the active mechanism; code that
 * does not exist cannot.
 *
 * <p><b>This class deliberately declares no multiplier constant</b>, and the
 * harness asserts its absence.
 *
 * <p>The reason for the change was that the old version could not be felt.
 * Halved growth speed is a shift in a probability the player never observes,
 * over tens of minutes, against a baseline they have no reading of. The
 * mechanism now lives in {@code BlightTouchedTrample}, which destroys crops the
 * player physically walks over -- instant, local, visible, and self-evidently
 * caused by them.
 *
 * <h2>Scope</h2>
 *
 * <p>Any crop, anywhere, at any growth stage, whether or not the player planted
 * it -- so cutting across a village farm ruins it. Wheat, carrots, potatoes,
 * beetroot, torchflower, pumpkin and melon stems (fruited or not), and the
 * pitcher crop. See {@code BlightTouchedTrample} for the block-level detail and
 * for why a dead bush placed on former farmland genuinely stays put.
 *
 * <p>Counterplay: walk around your fields rather than through them, and use a
 * path or a slab row. Nothing is destroyed unless the player's own feet enter
 * the block.
 *
 * <p>apply()/remove() are empty by design -- see {@link HookEffectBehavior}. The
 * effect is driven entirely by membership in the acquired set, which the trample
 * service checks once per tick before doing anything at all.
 */
public final class BlightTouchedBehavior extends HookEffectBehavior {

	public static final String ID = "blight_touched";
}
