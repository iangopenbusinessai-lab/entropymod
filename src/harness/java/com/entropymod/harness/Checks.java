package com.entropymod.harness;

import java.lang.reflect.Field;

/**
 * Minimal assertion plumbing for the headless checks. No test framework on
 * purpose -- this runs against the real Minecraft runtime classpath and the
 * fewer moving parts between it and the shipped classes, the better.
 */
final class Checks {

	private static int passed;
	private static int failed;
	private static String section = "(none)";

	static void section(String name) {
		section = name;
		System.out.println();
		System.out.println("== " + name);
	}

	static void check(boolean condition, String what) {
		if (condition) {
			passed++;
			System.out.println("  PASS  " + what);
		} else {
			failed++;
			System.out.println("  FAIL  [" + section + "] " + what);
		}
	}

	static void checkNear(double actual, double expected, double tolerance, String what) {
		boolean ok = Math.abs(actual - expected) <= tolerance;
		check(ok, what + "  (expected " + expected + ", got " + actual + ")");
	}

	/**
	 * Reads a constant by reflection rather than by referencing it.
	 *
	 * <p>This matters: {@code static final} primitives are inlined at the
	 * <em>caller's</em> compile time, so a harness that simply named
	 * {@code GreenThumbBehavior.MULTIPLIER} would be comparing its own compiled-in
	 * snapshot against itself and would pass no matter what the shipped class
	 * says. Reflection reads the field out of the loaded class file.
	 */
	static double constant(Class<?> owner, String name) {
		try {
			Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return ((Number) field.get(null)).doubleValue();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("No constant " + owner.getSimpleName() + "." + name, e);
		}
	}

	/**
	 * Whether a constant is declared at all. Used to assert a retired constant has
	 * genuinely been removed rather than merely set to a neutral value -- a
	 * neutral value can be quietly re-wired, an absent field cannot.
	 */
	static boolean hasConstant(Class<?> owner, String name) {
		try {
			owner.getDeclaredField(name);
			return true;
		} catch (NoSuchFieldException e) {
			return false;
		}
	}

	static int summary() {
		System.out.println();
		System.out.println("---------------------------------------------");
		System.out.println(passed + " passed, " + failed + " failed");
		return failed == 0 ? 0 : 1;
	}

	private Checks() {}
}
