package art.arcane.optics.math;

import java.util.List;


public class Box
{
	private double xa;
	private double xb;
	private double ya;
	private double yb;
	private double za;
	private double zb;

	public Box(double xa, double xb, double ya, double yb, double za, double zb)
	{
		this.xa = Math.min(xa, xb);
		this.xb = Math.max(xa, xb);
		this.ya = Math.min(ya, yb);
		this.yb = Math.max(ya, yb);
		this.za = Math.min(za, zb);
		this.zb = Math.max(za, zb);
	}

	public Box(AlignedPoint a, AlignedPoint b)
	{
		this(a.getX(), b.getX(), a.getY(), b.getY(), a.getZ(), b.getZ());
	}

	public Axis getThinAxis()
	{
		if(sizeX() < sizeZ() && sizeX() < sizeY())
		{
			return Axis.X;
		}

		if(sizeY() < sizeZ() && sizeY() < sizeX())
		{
			return Axis.Y;
		}

		if(sizeZ() < sizeX() && sizeZ() < sizeY())
		{
			return Axis.Z;
		}

		return null;
	}

	public Box(Box region)
	{
		this(region.min(), region.max());
	}

	public Box(Vec3 min, Vec3 max)
	{
		this(new AlignedPoint(min), new AlignedPoint(max));
	}

	public void encapsulate(Box b)
	{
		encapsulate(b.xa, b.ya, b.za, b.xb, b.yb, b.zb);
	}

	public void encapsulate(double x0, double y0, double z0, double x1, double y1, double z1)
	{
		double xMin = x0 < x1 ? x0 : x1;
		double yMin = y0 < y1 ? y0 : y1;
		double zMin = z0 < z1 ? z0 : z1;
		double xMax = x0 > x1 ? x0 : x1;
		double yMax = y0 > y1 ? y0 : y1;
		double zMax = z0 > z1 ? z0 : z1;
		if (xMin < xa) xa = xMin;
		if (yMin < ya) ya = yMin;
		if (zMin < za) za = zMin;
		if (xMax > xb) xb = xMax;
		if (yMax > yb) yb = yMax;
		if (zMax > zb) zb = zMax;
	}

	public void encapsulate(List<Vec3> b)
	{
		for(Vec3 i : b)
		{
			xa = i.getX() < xa ? i.getX() : xa;
			ya = i.getY() < ya ? i.getY() : ya;
			za = i.getZ() < za ? i.getZ() : za;
			xb = i.getX() > xb ? i.getX() : xb;
			yb = i.getY() > yb ? i.getY() : yb;
			zb = i.getZ() > zb ? i.getZ() : zb;
		}
	}

	public double getXa() { return xa; }
	public double getXb() { return xb; }
	public double getYa() { return ya; }
	public double getYb() { return yb; }
	public double getZa() { return za; }
	public double getZb() { return zb; }

	public Vec3 getCornerVector(Face x, Face y, Face z)
	{
		assert x.getAxis().equals(Axis.X) : " X direction must be on the X axis.";
		assert y.getAxis().equals(Axis.Y) : " Y direction must be on the Y axis.";
		assert z.getAxis().equals(Axis.Z) : " Z direction must be on the Z axis.";
		return new Vec3(x.x() == 1 ? xb : xa, y.y() == 1 ? yb : ya, z.z() == 1 ? zb : za);
	}

	public Vec3 random()
	{
		return new Vec3(M.rand(xa, xb), M.rand(ya, yb), M.rand(za, zb));
	}

	public Vec3 center()
	{
		return new Vec3(xa + (xb - xa) * 0.5D, ya + (yb - ya) * 0.5D, za + (zb - za) * 0.5D);
	}

	public Vec3 max()
	{
		return new Vec3(xb, yb, zb);
	}

	public Vec3 min()
	{
		return new Vec3(xa, ya, za);
	}

	public Box getFace(Face d)
	{
		return getFace(d, 0);
	}

	public Box getFace(Face d, double depth)
	{
		switch(d.getAxis())
		{
			case X:
				return new Box(d.x() == 1 ? xb : xa, d.x() == 1 ? xb : xa, ya, yb, za, zb);
			case Y:
				return new Box(xa, xb, d.y() == 1 ? yb : ya, d.y() == 1 ? yb : ya, za, zb);
			case Z:
				return new Box(xa, xb, ya, yb, d.z() == 1 ? zb : za, d.z() == 1 ? zb : za);
		}

		return this;
	}

	public boolean contains(Vec3 p)
	{
		return p.getX() >= xa && p.getX() <= xb && p.getY() >= ya && p.getY() <= yb && p.getZ() >= za && p.getZ() <= zb;
	}

	public boolean containsPrimitive(double x, double y, double z)
	{
		return x >= xa && x <= xb && y >= ya && y <= yb && z >= za && z <= zb;
	}

	public boolean intersects(Box s)
	{
		return this.xb >= s.xa && this.yb >= s.ya && this.zb >= s.za && s.xb >= this.xa && s.yb >= this.ya && s.zb >= this.za;
	}

	public double sizeX()
	{
		return xb - xa;
	}

	public double sizeY()
	{
		return yb - ya;
	}

	public double sizeZ()
	{
		return zb - za;
	}

	public double volume()
	{
		return sizeX() * sizeY() * sizeZ();
	}
}
