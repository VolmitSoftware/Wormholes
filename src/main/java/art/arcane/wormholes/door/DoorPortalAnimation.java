package art.arcane.wormholes.door;

import art.arcane.wormholes.door.DoorPortalVisualService.PortalPlaneGeometry;
import org.bukkit.block.BlockFace;

import java.util.Objects;

/**
 * Pure per-tick math for the animated surface of a live dimensional door.
 *
 * <p>The face passed in is the surface normal of the panel, not the block's
 * facing: a hinged door hands in its cardinal facing, while a trapdoor's panel
 * lies flat and hands in {@link BlockFace#UP}. A flat panel pulses across both
 * horizontal axes and holds its thickness instead.</p>
 */
final class DoorPortalAnimation
{
	static final int FRAME_PERIOD_TICKS = 2;
	static final int ATTENDANCE_PERIOD_TICKS = 40;
	static final double ATTENDANCE_RANGE_SQUARED = 128.0D * 128.0D;
	static final int PULSE_PERIOD_TICKS = 48;
	static final float PULSE_DEPTH = 0.06F;
	static final int SWAY_PERIOD_TICKS = 72;
	static final float SWAY_AMPLITUDE = 0.008F;
	static final int ORBIT_PERIOD_TICKS = 44;
	static final int ORBIT_ARMS = 2;
	static final double ORBIT_LATERAL_FRACTION = 0.36D;
	static final double ORBIT_VERTICAL_FRACTION = 0.4D;
	private static final double TAU = Math.PI * 2.0D;

	private DoorPortalAnimation()
	{
	}

	static PortalPlaneGeometry frame(PortalPlaneGeometry base, BlockFace facing, int tick)
	{
		Objects.requireNonNull(base, "base");
		float pulse = 1.0F - (PULSE_DEPTH * (0.5F + (0.5F * (float) Math.sin((tick * TAU) / PULSE_PERIOD_TICKS))));
		float sway = SWAY_AMPLITUDE * (float) Math.sin(((tick * TAU) / SWAY_PERIOD_TICKS) + (Math.PI / 3.0D));
		return switch(requireSupported(facing))
		{
			case UP, DOWN ->
			{
				float scaleX = base.scaleX() * pulse;
				float scaleZ = base.scaleZ() * pulse;
				yield new PortalPlaneGeometry(
					base.translationX() + ((base.scaleX() - scaleX) / 2.0F) + sway,
					base.translationY(),
					base.translationZ() + ((base.scaleZ() - scaleZ) / 2.0F),
					scaleX,
					base.scaleY(),
					scaleZ);
			}
			case NORTH, SOUTH ->
			{
				float scaleX = base.scaleX() * pulse;
				float scaleY = base.scaleY() * pulse;
				yield new PortalPlaneGeometry(
					base.translationX() + ((base.scaleX() - scaleX) / 2.0F) + sway,
					base.translationY() + ((base.scaleY() - scaleY) / 2.0F),
					base.translationZ(),
					scaleX,
					scaleY,
					base.scaleZ());
			}
			default ->
			{
				float scaleY = base.scaleY() * pulse;
				float scaleZ = base.scaleZ() * pulse;
				yield new PortalPlaneGeometry(
					base.translationX(),
					base.translationY() + ((base.scaleY() - scaleY) / 2.0F),
					base.translationZ() + ((base.scaleZ() - scaleZ) / 2.0F) + sway,
					base.scaleX(),
					scaleY,
					scaleZ);
			}
		};
	}

	static double[] orbitPoint(PortalPlaneGeometry base, BlockFace facing, int tick, int arm)
	{
		Objects.requireNonNull(base, "base");
		if(arm < 0 || arm >= ORBIT_ARMS)
		{
			throw new IllegalArgumentException("Orbit arm must be within [0, " + ORBIT_ARMS + "): " + arm);
		}
		double direction = (arm & 1) == 0 ? 1.0D : -1.0D;
		double angle = (direction * ((tick * TAU) / ORBIT_PERIOD_TICKS)) + (arm * (TAU / ORBIT_ARMS));
		return switch(requireSupported(facing))
		{
			case UP, DOWN ->
			{
				double lateralCenter = base.translationX() + (base.scaleX() / 2.0D);
				double lateralRadius = base.scaleX() * ORBIT_LATERAL_FRACTION;
				double depthCenter = base.translationZ() + (base.scaleZ() / 2.0D);
				double depthRadius = base.scaleZ() * ORBIT_VERTICAL_FRACTION;
				yield new double[] {
					lateralCenter + (lateralRadius * Math.cos(angle)),
					base.translationY() + (base.scaleY() / 2.0D),
					depthCenter + (depthRadius * Math.sin(angle))};
			}
			case NORTH, SOUTH ->
			{
				double lateralCenter = base.translationX() + (base.scaleX() / 2.0D);
				double lateralRadius = base.scaleX() * ORBIT_LATERAL_FRACTION;
				yield new double[] {
					lateralCenter + (lateralRadius * Math.cos(angle)),
					verticalOrbit(base, angle),
					base.translationZ() + (base.scaleZ() / 2.0D)};
			}
			default ->
			{
				double lateralCenter = base.translationZ() + (base.scaleZ() / 2.0D);
				double lateralRadius = base.scaleZ() * ORBIT_LATERAL_FRACTION;
				yield new double[] {
					base.translationX() + (base.scaleX() / 2.0D),
					verticalOrbit(base, angle),
					lateralCenter + (lateralRadius * Math.cos(angle))};
			}
		};
	}

	static double[] scatterPoint(PortalPlaneGeometry base, BlockFace facing, double u, double v)
	{
		Objects.requireNonNull(base, "base");
		if(u < 0.0D || u >= 1.0D || v < 0.0D || v >= 1.0D)
		{
			throw new IllegalArgumentException("Scatter coordinates must be within [0, 1): " + u + ", " + v);
		}
		return switch(requireSupported(facing))
		{
			case UP, DOWN -> new double[] {
				base.translationX() + (u * base.scaleX()),
				base.translationY() + (base.scaleY() / 2.0D),
				base.translationZ() + (v * base.scaleZ())};
			case NORTH, SOUTH -> new double[] {
				base.translationX() + (u * base.scaleX()),
				base.translationY() + (v * base.scaleY()),
				base.translationZ() + (base.scaleZ() / 2.0D)};
			default -> new double[] {
				base.translationX() + (base.scaleX() / 2.0D),
				base.translationY() + (v * base.scaleY()),
				base.translationZ() + (u * base.scaleZ())};
		};
	}

	private static double verticalOrbit(PortalPlaneGeometry base, double angle)
	{
		double verticalCenter = base.translationY() + (base.scaleY() / 2.0D);
		return verticalCenter + (base.scaleY() * ORBIT_VERTICAL_FRACTION * Math.sin(angle));
	}

	private static BlockFace requireSupported(BlockFace facing)
	{
		Objects.requireNonNull(facing, "facing");
		return switch(facing)
		{
			case NORTH, SOUTH, EAST, WEST, UP, DOWN -> facing;
			default -> throw new IllegalArgumentException("Door portal facing must be axial: " + facing);
		};
	}
}
