package art.arcane.wormholes.util;

import art.arcane.wormholes.geometry.GeometryVector;

import java.util.List;

/**
 * Directions
 *
 * @author cyberpwn
 */
public enum Direction
{
	U(0, 1, 0),
	D(0, -1, 0),
	N(0, 0, -1),
	S(0, 0, 1),
	E(1, 0, 0),
	W(-1, 0, 0);

	private final int x;
	private final int y;
	private final int z;

	public float toYaw()
	{
		switch(this)
		{
			case S:
				return 0f;
			case W:
				return 90f;
			case N:
				return 180f;
			case E:
				return -90f;
			default:
				return 0f;
		}
	}

	@Override
	public String toString()
	{
		switch(this)
		{
			case D:
				return "Down";
			case E:
				return "East";
			case N:
				return "North";
			case S:
				return "South";
			case U:
				return "Up";
			case W:
				return "West";
		}

		return "?";
	}

	public boolean isVertical()
	{
		return equals(D) || equals(U);
	}

	public static Direction closest(GeometryVector v)
	{
		return closest(v.getX(), v.getY(), v.getZ());
	}

	public static Direction closest(double x, double y, double z)
	{
		double ax = Math.abs(x);
		double ay = Math.abs(y);
		double az = Math.abs(z);

		if(ay >= ax && ay >= az)
		{
			return y >= 0.0D ? U : D;
		}

		if(ax >= az)
		{
			return x >= 0.0D ? E : W;
		}

		return z >= 0.0D ? S : N;
	}

	public static Direction closest(GeometryVector v, Direction... d)
	{
		double m = Double.MAX_VALUE;
		Direction s = null;

		for(Direction i : d)
		{
			GeometryVector x = i.toVector();
			double g = x.distance(v);

			if(g < m)
			{
				m = g;
				s = i;
			}
		}

		return s;
	}

	public static Direction closest(GeometryVector v, List<Direction> d)
	{
		double m = Double.MAX_VALUE;
		Direction s = null;

		for(Direction i : d)
		{
			GeometryVector x = i.toVector();
			double g = x.distance(v);

			if(g < m)
			{
				m = g;
				s = i;
			}
		}

		return s;
	}

	public GeometryVector toVector()
	{
		return new GeometryVector(x, y, z);
	}

	private Direction(int x, int y, int z)
	{
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public Direction reverse()
	{
		switch(this)
		{
			case D:
				return U;
			case E:
				return W;
			case N:
				return S;
			case S:
				return N;
			case U:
				return D;
			case W:
				return E;
			default:
				break;
		}

		return null;
	}

	public int x()
	{
		return x;
	}

	public int y()
	{
		return y;
	}

	public int z()
	{
		return z;
	}

	public static Direction getDirection(GeometryVector v)
	{
		GeometryVector normalized = v.normalize();
		GeometryVector k = new GeometryVector(Math.signum(normalized.getX()), Math.signum(normalized.getY()), Math.signum(normalized.getZ()));

		for(Direction i : udnews())
		{
			if(i.x == k.getBlockX() && i.y == k.getBlockY() && i.z == k.getBlockZ())
			{
				return i;
			}
		}

		return Direction.N;
	}

	public static List<Direction> udnews()
	{
		return List.of(U, D, N, E, W, S);
	}

	/**
	 * Get the directional value from the given byte from common directional blocks
	 * (MUST BE BETWEEN 0 and 5 INCLUSIVE)
	 *
	 * @param b
	 *            the byte
	 * @return the direction or null if the byte is outside of the inclusive range
	 *         0-5
	 */
	public static Direction fromByte(byte b)
	{
		if(b > 5 || b < 0)
		{
			return null;
		}

		if(b == 0)
		{
			return D;
		}

		else if(b == 1)
		{
			return U;
		}

		else if(b == 2)
		{
			return N;
		}

		else if(b == 3)
		{
			return S;
		}

		else if(b == 4)
		{
			return W;
		}

		else
		{
			return E;
		}
	}

	/**
	 * Get the byte value represented in some directional blocks
	 *
	 * @return the byte value
	 */
	public byte byteValue()
	{
		switch(this)
		{
			case D:
				return 0;
			case E:
				return 5;
			case N:
				return 2;
			case S:
				return 3;
			case U:
				return 1;
			case W:
				return 4;
			default:
				break;
		}

		return -1;
	}

	public Axis getAxis()
	{
		switch(this)
		{
			case D:
				return Axis.Y;
			case E:
				return Axis.X;
			case N:
				return Axis.Z;
			case S:
				return Axis.Z;
			case U:
				return Axis.Y;
			case W:
				return Axis.X;
		}

		return null;
	}

}
