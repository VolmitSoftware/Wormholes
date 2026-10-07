package art.arcane.optics.frame;


import art.arcane.optics.math.Vec3d;

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

	public static Frame fromDirectionAndLook(Face normal, Vec3d look) {
		if (!normal.isVertical()) {
			return canonical(normal);
		}
		Face up = verticalFallbackUp(normal);
		if (look != null) {
			double x = look.x();
			double z = look.z();
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
