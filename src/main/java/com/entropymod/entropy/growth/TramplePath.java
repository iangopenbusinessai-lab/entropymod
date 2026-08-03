package com.entropymod.entropy.growth;

/**
 * The set of block cells a player passed through between two ticks.
 *
 * <p>A single "what block am I standing in" check per tick is correct for
 * walking and sprinting -- 0.28 blocks per tick means three or four consecutive
 * ticks land inside the same block -- but it is wrong for anything faster.
 * Elytra flight, a horse, and a boat on ice all cover more than a block per
 * tick, so a player flying over a field would skip most of it. Sampling the
 * segment between the previous tick's position and this one's is what makes
 * "walked through" mean the same thing at every speed.
 *
 * <p><b>Deliberately free of Minecraft imports</b>, the same discipline
 * {@link CropSchedule}, {@code AcquiredEffects} and {@code EntropyPalette}
 * follow. The rules here -- no gaps at speed, no repeated work when standing
 * still, and a teleport not counting as a walk -- are the ones most likely to be
 * got wrong, and this way the harness drives the real shipped class rather than
 * a copy of it.
 *
 * <h2>Known limit, stated rather than hidden</h2>
 *
 * <p>This samples the segment at fixed intervals rather than performing an exact
 * voxel traversal. A cell the path clips by less than {@value #MAX_STEP} of a
 * block at a corner can be missed. That is a deliberate trade: the exact
 * algorithm is several times the code for an outcome no player can distinguish
 * from a near-miss, and the common cases -- standing, walking, sprinting, and
 * flying in a straight line -- are all exact.
 */
public final class TramplePath {

	/** Receives each distinct cell the path crossed, in order of travel. */
	@FunctionalInterface
	public interface CellVisitor {
		void visit(int x, int y, int z);
	}

	/**
	 * Longest distance along any single axis between two samples. A quarter of a
	 * block, so a straight run through a block always produces at least four
	 * samples inside it.
	 */
	public static final double MAX_STEP = 0.25;

	/**
	 * Beyond this many blocks on any axis in one tick, the movement is treated as
	 * a discontinuity rather than a path.
	 *
	 * <p>A teleport, a portal, a respawn and a {@code /tp} all present as an
	 * enormous single-tick delta, and the player did not walk through anything in
	 * between -- ruining every crop on the line between two points is not the
	 * effect. Only the destination cell is visited in that case. The threshold is
	 * comfortably above any real movement speed: the fastest vanilla travel, a
	 * riptide trident, is around 5 blocks per tick.
	 */
	public static final double MAX_SEGMENT = 16.0;

	/**
	 * Visits every distinct block cell on the segment from {@code (x0,y0,z0)} to
	 * {@code (x1,y1,z1)}, inclusive of both ends, in order of travel.
	 *
	 * <p>Always visits at least one cell -- the destination -- so a stationary
	 * player still has the block under their feet checked. Consecutive duplicates
	 * are collapsed, so standing still costs exactly one visit per tick rather
	 * than one per sample.
	 *
	 * <p>If the two points are further apart than {@link #MAX_SEGMENT} on any
	 * axis, only the destination cell is visited -- see that constant.
	 */
	public static void forEachCell(double x0, double y0, double z0,
								   double x1, double y1, double z1,
								   CellVisitor visitor) {
		if (span(x0, y0, z0, x1, y1, z1) > MAX_SEGMENT) {
			visitor.visit(floor(x1), floor(y1), floor(z1));
			return;
		}

		int steps = stepsFor(x0, y0, z0, x1, y1, z1);
		int lastX = 0;
		int lastY = 0;
		int lastZ = 0;
		boolean any = false;

		for (int i = 0; i <= steps; i++) {
			double t = (double) i / steps;
			int cx = floor(x0 + (x1 - x0) * t);
			int cy = floor(y0 + (y1 - y0) * t);
			int cz = floor(z0 + (z1 - z0) * t);

			if (any && cx == lastX && cy == lastY && cz == lastZ) {
				continue;
			}
			visitor.visit(cx, cy, cz);
			lastX = cx;
			lastY = cy;
			lastZ = cz;
			any = true;
		}
	}

	/**
	 * How many samples the segment is split into: enough that no step exceeds
	 * {@link #MAX_STEP} on any axis, and never fewer than one.
	 *
	 * <p>Driven by the largest axis delta rather than the euclidean distance, so a
	 * purely diagonal move is sampled as finely as a straight one.
	 *
	 * <p>Public so the harness can assert the sample count directly rather than
	 * inferring it from the cells that come out. Says nothing about the teleport
	 * case, which {@link #forEachCell} short-circuits before reaching this.
	 */
	public static int stepsFor(double x0, double y0, double z0,
							   double x1, double y1, double z1) {
		return Math.max(1, (int) Math.ceil(span(x0, y0, z0, x1, y1, z1) / MAX_STEP));
	}

	/** The largest single-axis distance between the two points. */
	private static double span(double x0, double y0, double z0,
							   double x1, double y1, double z1) {
		return Math.max(Math.abs(x1 - x0),
				Math.max(Math.abs(y1 - y0), Math.abs(z1 - z0)));
	}

	/**
	 * Floor to a block coordinate. Hand-rolled rather than {@code Mth.floor} only
	 * because this class stays free of Minecraft imports; the behaviour for
	 * negative coordinates is the same, and getting that wrong would shift every
	 * cell by one on the negative side of the world.
	 */
	private static int floor(double value) {
		int truncated = (int) value;
		return value < truncated ? truncated - 1 : truncated;
	}

	private TramplePath() {}
}
