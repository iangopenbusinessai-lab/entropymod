package com.entropymod.entropy;

/**
 * Categories used for the "max one active effect per category" anti-stacking rule.
 * When a new effect is applied, any currently-active effect in the same category
 * is expired/removed first.
 */
public enum EffectCategory {
	MOVEMENT,
	SURVIVAL,
	TOOL,
	COMBAT,
	GEAR,
	COMPANION,
	UTILITY,
	DEBUFF,
	META
}
