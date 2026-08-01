package com.entropymod.entropy;

/**
 * The loop alternates GOOD -> BAD -> GOOD -> BAD ... every interval.
 * Phase is determined by whether the pick count so far is even or odd
 * (see EntropyManager#currentPhase).
 */
public enum EffectPhase {
	GOOD,
	BAD
}
