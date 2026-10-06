package art.arcane.optics.math;


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
	
	public Vec3 positive()
	{
		return new Vec3(x, y, z);
	}
	
	public Vec3 negative()
	{
		return new Vec3(-x, -y, -z);
	}
}
