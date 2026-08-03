package com.entropymod.harness;

/**
 * Vanilla's crop-growth timing, reproduced from bytecode so the Green Thumb /
 * Blight Touched multipliers can be derived rather than guessed.
 *
 * <p>Every constant here was read out of the shipped jar with {@code javap}, not
 * from a wiki:
 *
 * <ul>
 *   <li>{@code CropBlock.randomTick}, {@code StemBlock.randomTick} and
 *       {@code PitcherCropBlock.randomTick} all roll
 *       {@code random.nextInt((int)(25.0F / getGrowthSpeed(...)) + 1) == 0}.</li>
 *   <li>{@code ServerLevel.tickChunk} draws {@code random_tick_speed} positions
 *       per game tick from each 16x16x16 section, uniformly over its 4096
 *       blocks.</li>
 *   <li>{@code GameRules.RANDOM_TICK_SPEED} is registered with a default of
 *       3.</li>
 *   <li>{@code BeetrootBlock.randomTick} and
 *       {@code TorchflowerCropBlock.randomTick} gate on
 *       {@code nextInt(3) != 0} before delegating to {@code CropBlock} -- so two
 *       thirds of their random ticks reach the growth roll.</li>
 *   <li>{@code getMaxAge()} is 7 for {@code CropBlock} (wheat, carrots,
 *       potatoes), 3 for beetroot, 2 for torchflower, 7 for stems, 4 for the
 *       pitcher crop.</li>
 * </ul>
 *
 * <p>Kept free of Minecraft imports so the model is readable on its own; it is
 * checked against a known-good external number in {@link HarnessMain} (vanilla
 * row-planted wheat, ~24 minutes) rather than trusted blind.
 */
final class CropGrowthModel {

	/** {@code GameRules.RANDOM_TICK_SPEED} registered default. */
	static final int RANDOM_TICK_SPEED = 3;

	/** Blocks in one 16x16x16 chunk section -- the pool each random tick draws from. */
	static final int SECTION_BLOCKS = 16 * 16 * 16;

	static final double TICKS_PER_SECOND = 20.0;

	/** Vanilla's numerator in {@code (int)(25.0F / speed)}. */
	static final float ROLL_NUMERATOR = 25.0f;

	/** Expected game ticks between random ticks of one specific block. */
	static double ticksPerRandomTick() {
		return (double) SECTION_BLOCKS / RANDOM_TICK_SPEED;
	}

	/** Expected seconds between random ticks of one specific block. */
	static double secondsPerRandomTick() {
		return ticksPerRandomTick() / TICKS_PER_SECOND;
	}

	/**
	 * The bound vanilla passes to {@code nextInt}. A growth roll succeeds with
	 * probability {@code 1 / bound}, so this is also the expected number of growth
	 * rolls per stage advance.
	 */
	static int rollBound(float growthSpeed) {
		return (int) (ROLL_NUMERATOR / growthSpeed) + 1;
	}

	/** True once the roll bound has bottomed out at 1, i.e. every random tick grows the crop. */
	static boolean saturated(float growthSpeed) {
		return rollBound(growthSpeed) == 1;
	}

	/**
	 * Expected seconds from freshly planted to mature.
	 *
	 * @param stages    stage advances needed
	 * @param gate      probability a random tick reaches the growth roll at all
	 *                  (1.0 normally; 2/3 for beetroot and torchflower)
	 * @param baseSpeed what {@code CropBlock.getGrowthSpeed} returns for the layout
	 * @param modMultiplier what this mod scales that by
	 */
	static double expectedSeconds(int stages, double gate, float baseSpeed, float modMultiplier) {
		int bound = rollBound(baseSpeed * modMultiplier);
		return stages * bound / gate * secondsPerRandomTick();
	}

	static String asMinutesSeconds(double seconds) {
		long whole = Math.round(seconds);
		return (whole / 60) + "m " + String.format("%02d", whole % 60) + "s";
	}

	/** One covered crop type and what it takes to mature. */
	record Crop(String name, int stages, double gate) {}

	/**
	 * Everything {@code CropBlock.getGrowthSpeed} covers, which is the exact scope
	 * of the mixin -- {@code CropBlock}, {@code StemBlock} and
	 * {@code PitcherCropBlock} are its only three callers.
	 *
	 * <p>The stem entry counts 8 rather than 7: reaching age 7 takes 7 successful
	 * rolls and placing the first fruit takes one more.
	 */
	static final Crop[] COVERED = {
			new Crop("Wheat / carrots / potatoes", 7, 1.0),
			new Crop("Beetroot", 3, 2.0 / 3.0),
			new Crop("Torchflower", 2, 2.0 / 3.0),
			new Crop("Pumpkin / melon stem (incl. first fruit)", 8, 1.0),
			new Crop("Pitcher crop", 4, 1.0),
	};

	// ------------------------------------------------------------------
	// The ACTIVE mechanism (GreenThumbGrowth), which does not use any of the
	// probability machinery above -- that is the whole point of it.
	//
	// A granted advance is not rolled for, so the expected total is simply
	// stages x interval. Two consequences worth stating because they are what
	// makes uniform 90s reachable where the multiplier could not:
	//
	//   * The 68.27s random-tick rate does not apply. Advances arrive on this
	//     mod's own schedule.
	//   * Beetroot's and torchflower's 2/3 randomTick gate does not apply either.
	//     That gate lives in their randomTick override, and a granted advance goes
	//     straight to the state transition instead.
	// ------------------------------------------------------------------

	/**
	 * One covered crop under the active mechanism.
	 *
	 * @param stages      stage advances that can be granted directly
	 * @param budgetTicks the tick budget those advances are divided into
	 * @param extraTicks  time outside the granted advances (the stem's fruit step)
	 */
	record ActiveCrop(String name, int stages, int budgetTicks, int extraTicks) {}

	/** Expected total ticks from freshly planted to mature under the active mechanism. */
	static int activeTotalTicks(ActiveCrop crop, int intervalTicks) {
		return crop.stages() * intervalTicks + crop.extraTicks();
	}

	/** Base growth speed of a fully-planted field on hydrated farmland: 1 + 3 + 8*(3/4), halved for crops on both axes. */
	static final float BASE_SPEED_PACKED_FIELD = 5.0f;

	/** Base growth speed of row planting on hydrated farmland -- no crossing neighbours, so no halving. */
	static final float BASE_SPEED_ROWS = 10.0f;

	/** Worst real farmland layout: dry, isolated, crops on both axes. 1 + 1, halved. */
	static final float BASE_SPEED_WORST_FARMLAND = 1.0f;

	private CropGrowthModel() {}
}
