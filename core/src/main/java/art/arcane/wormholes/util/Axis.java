package art.arcane.wormholes.util;

import art.arcane.wormholes.geometry.GeometryVector;

public enum Axis
{
	X(1, 0, 0),
	Y(0, 1, 0),
	Z(0, 0, 1);
	
	private final int x;
	private final int y;
	private final int z;
	
	private Axis(int x, int y, int z)
	{
		this.x = x;
		this.y = y;
		this.z = z;
	}
	
	public GeometryVector positive()
	{
		return new GeometryVector(x, y, z);
	}
	
	public GeometryVector negative()
	{
		return new GeometryVector(-x, -y, -z);
	}
}
