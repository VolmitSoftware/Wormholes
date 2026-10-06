package art.arcane.optics.frame;

import art.arcane.optics.math.Vec3;

import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

public final class Frame {
	private final Face normal;
	private final Face right;
	private final Face up;

	public Frame(Face normal, Face right, Face up) {
		this.normal = requireDirection(normal);
		this.right = requireDirection(right);
		this.up = requireDirection(up);
		if (!isPerpendicular(this.normal, this.right) || !isPerpendicular(this.normal, this.up) || !isPerpendicular(this.right, this.up)) {
			throw new IllegalArgumentException("Portal frame directions must be perpendicular");
		}
		Face expectedRight = cross(this.normal, this.up);
		if (!expectedRight.equals(this.right)) {
			throw new IllegalArgumentException("Portal frame right must equal normal cross up");
		}
	}

	public static Frame canonical(Face normal) {
		Face up = normal.isVertical() ? verticalFallbackUp(normal) : Face.U;
		return fromNormalUp(normal, up);
	}

	public static Frame derive(Box area, Face normal) {
		if (!normal.isVertical() || area == null) {
			return canonical(normal);
		}
		double xSpan = area.sizeX();
		double zSpan = area.sizeZ();
		if (xSpan > zSpan + 1e-6D) {
			return fromNormalUp(normal, Face.E);
		}
		return canonical(normal);
	}

	public static Frame fromDirectionAndLook(Face normal, Vec3 look) {
		if (!normal.isVertical()) {
			return canonical(normal);
		}
		Face up = verticalFallbackUp(normal);
		if (look != null) {
			double x = look.getX();
			double z = look.getZ();
			double horizontal = Math.sqrt(x * x + z * z);
			if (horizontal > 1e-6D) {
				up = Face.closest(x, 0.0D, z);
				if (up.isVertical()) {
					up = verticalFallbackUp(normal);
				}
			}
		}
		return fromNormalUp(normal, up);
	}

	public static Frame fromNormalUp(Face normal, Face up) {
		return new Frame(normal, cross(normal, up), up);
	}


	public Face getNormal() {
		return normal;
	}

	public Face getRight() {
		return right;
	}

	public Face getUp() {
		return up;
	}

	public Frame flipNormal() {
		return fromNormalUp(normal.reverse(), up);
	}

	public Frame view(boolean frontSide) {
		return frontSide ? this : flipNormal();
	}

	public Frame rotateClockwise() {
		return fromNormalUp(normal, right);
	}

	public Frame rotateCounterClockwise() {
		return fromNormalUp(normal, right.reverse());
	}

	public Frame withNormal(Face newNormal) {
		Face nextNormal = requireDirection(newNormal);
		if (isPerpendicular(nextNormal, up)) {
			return fromNormalUp(nextNormal, up);
		}
		if (isPerpendicular(nextNormal, normal)) {
			return fromNormalUp(nextNormal, normal);
		}
		return canonical(nextNormal);
	}

	public Vec3 transformPoint(Vec3 point, Vec3 fromOrigin, Vec3 toOrigin, Frame to) {
		double[] out = new double[3];
		transformPointInto(point.getX(), point.getY(), point.getZ(),
			fromOrigin.getX(), fromOrigin.getY(), fromOrigin.getZ(),
			toOrigin.getX(), toOrigin.getY(), toOrigin.getZ(), to, out);
		return new Vec3(out[0], out[1], out[2]);
	}

	public Vec3 transformCrossingPoint(Vec3 point, Vec3 fromOrigin, Vec3 toOrigin, Frame to) {
		double distance = dot(point.x() - fromOrigin.x(), point.y() - fromOrigin.y(), point.z() - fromOrigin.z(), normal);
		Vec3 crossing = new Vec3(point.x() - distance * normal.x(), point.y() - distance * normal.y(),
			point.z() - distance * normal.z());
		return view(distance >= 0.0D).transformPoint(crossing, fromOrigin, toOrigin, to);
	}

	public void transformPointInto(double x, double y, double z,
								   double fromOriginX, double fromOriginY, double fromOriginZ,
								   double toOriginX, double toOriginY, double toOriginZ,
								   Frame to, double[] out3) {
		double offsetX = x - fromOriginX;
		double offsetY = y - fromOriginY;
		double offsetZ = z - fromOriginZ;
		transformVectorInto(offsetX, offsetY, offsetZ, to, out3);
		out3[0] = toOriginX + out3[0];
		out3[1] = toOriginY + out3[1];
		out3[2] = toOriginZ + out3[2];
	}

	public Vec3 transformVector(Vec3 vector, Frame to) {
		double[] out = new double[3];
		transformVectorInto(vector.getX(), vector.getY(), vector.getZ(), to, out);
		return new Vec3(out[0], out[1], out[2]);
	}

	public void transformVectorInto(double x, double y, double z, Frame to, double[] out3) {
		double frameRight = dot(x, y, z, right);
		double frameUp = dot(x, y, z, up);
		double frameNormal = dot(x, y, z, normal);
		out3[0] = frameRight * to.right.x() + frameUp * to.up.x() + frameNormal * to.normal.x();
		out3[1] = frameRight * to.right.y() + frameUp * to.up.y() + frameNormal * to.normal.y();
		out3[2] = frameRight * to.right.z() + frameUp * to.up.z() + frameNormal * to.normal.z();
	}

	public Face transformDirection(Face direction, Frame to, double[] scratch3) {
		transformVectorInto(direction.x(), direction.y(), direction.z(), to, scratch3);
		return Face.closest(scratch3[0], scratch3[1], scratch3[2]);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Frame frame && normal == frame.normal && right == frame.right && up == frame.up;
	}

	@Override
	public int hashCode() {
		return (normal.ordinal() * 31 + right.ordinal()) * 31 + up.ordinal();
	}

	private static Face requireDirection(Face direction) {
		if (direction == null) {
			throw new IllegalArgumentException("Portal frame direction cannot be null");
		}
		return direction;
	}

	private static Face verticalFallbackUp(Face normal) {
		return normal.equals(Face.D) ? Face.N : Face.S;
	}

	private static boolean isPerpendicular(Face a, Face b) {
		return dot(a.x(), a.y(), a.z(), b) == 0.0D;
	}

	private static double dot(double x, double y, double z, Face direction) {
		return x * direction.x() + y * direction.y() + z * direction.z();
	}

	private static Face cross(Face a, Face b) {
		int x = a.y() * b.z() - a.z() * b.y();
		int y = a.z() * b.x() - a.x() * b.z();
		int z = a.x() * b.y() - a.y() * b.x();
		if (x == 0 && y == 0 && z == 0) {
			throw new IllegalArgumentException("Portal frame normal and up cannot be parallel");
		}
		return Face.closest(x, y, z);
	}
}
