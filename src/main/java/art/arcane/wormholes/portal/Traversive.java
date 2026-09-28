package art.arcane.wormholes.portal;

import art.arcane.wormholes.util.BukkitGeometry;

import java.util.UUID;

import art.arcane.wormholes.network.WireTraversive;

import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import art.arcane.wormholes.util.Direction;

public class Traversive
{
	private final Object object;
	private final TraversableType type;
	private final PortalFrame inFrame;
	private final Vector inOrigin;
	private final Vector inPoint;
	private final Vector inVelocity;
	private final Vector inLook;
	private final boolean frontSide;
	private final UUID sourcePortalId;

	public Traversive(Object o, TraversableType type, Direction inDirection, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook)
	{
		this(o, type, PortalFrame.canonical(inDirection), inOrigin, inPoint, inVelocity, inLook, true);
	}

	public Traversive(Object o, TraversableType type, PortalFrame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook)
	{
		this(o, type, inFrame, inOrigin, inPoint, inVelocity, inLook, true);
	}

	public Traversive(Object o, TraversableType type, PortalFrame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook, boolean frontSide)
	{
		this(o, type, inFrame, inOrigin, inPoint, inVelocity, inLook, frontSide, null);
	}

	public Traversive(Object o, TraversableType type, PortalFrame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook, boolean frontSide, UUID sourcePortalId)
	{
		this.object = o;
		this.type = type;
		this.inFrame = inFrame;
		this.inOrigin = inOrigin.clone();
		this.inPoint = inPoint.clone();
		this.inVelocity = inVelocity.clone();
		this.inLook = inLook.clone();
		this.frontSide = frontSide;
		this.sourcePortalId = sourcePortalId;
	}

	public Traversive(Entity entity, Direction inDirection, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook)
	{
		this(entity, TraversableType.ENTITY, inDirection, inOrigin, inPoint, inVelocity, inLook);
	}

	public Traversive(Entity entity, PortalFrame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook)
	{
		this(entity, TraversableType.ENTITY, inFrame, inOrigin, inPoint, inVelocity, inLook);
	}

	public Traversive(Entity entity, PortalFrame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook, boolean frontSide)
	{
		this(entity, TraversableType.ENTITY, inFrame, inOrigin, inPoint, inVelocity, inLook, frontSide);
	}

	public Traversive(Entity entity, PortalFrame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook, boolean frontSide, UUID sourcePortalId)
	{
		this(entity, TraversableType.ENTITY, inFrame, inOrigin, inPoint, inVelocity, inLook, frontSide, sourcePortalId);
	}

    public static WireTraversive toWire(Traversive t) {
        return WireTraversive.fromCrossing(t.crossing());
    }

    public static Traversive fromWire(WireTraversive wire, Object object) {
        PortalFrame frame = new PortalFrame(Direction.valueOf(wire.frameNormal()), Direction.valueOf(wire.frameRight()), Direction.valueOf(wire.frameUp()));
        return new Traversive(
            object,
            TraversableType.ENTITY,
            frame,
            new Vector(wire.originX(), wire.originY(), wire.originZ()),
            new Vector(wire.pointX(), wire.pointY(), wire.pointZ()),
            new Vector(wire.velocityX(), wire.velocityY(), wire.velocityZ()),
            new Vector(wire.lookX(), wire.lookY(), wire.lookZ()),
            wire.frontSide()
        );
    }

	/** A copy of this crossing for another rig member at {@code memberPoint}, keeping frame, velocity, look, and source. */
	public Traversive forMember(Object member, Vector memberPoint)
	{
		return new Traversive(member, TraversableType.ENTITY, inFrame, inOrigin, memberPoint, inVelocity, inLook, frontSide, sourcePortalId);
	}

    public PortalCrossing crossing() {
        return new PortalCrossing(inFrame, BukkitGeometry.vector(inOrigin), BukkitGeometry.vector(inPoint),
            BukkitGeometry.vector(inVelocity), BukkitGeometry.vector(inLook), frontSide);
    }

	public Vector getOutVelocity(Direction outDirection)
	{
		return getOutVelocity(PortalFrame.canonical(outDirection));
	}

	public Vector getOutVelocity(PortalFrame outFrame)
	{
		return BukkitGeometry.bukkit(inFrame.transformVector(BukkitGeometry.vector(getInVelocity()), outFrame.view(frontSide)));
	}

	public Vector getOutLook(Direction outDirection)
	{
		return getOutLook(PortalFrame.canonical(outDirection));
	}

	public Vector getOutLook(PortalFrame outFrame)
	{
		return BukkitGeometry.bukkit(inFrame.transformVector(BukkitGeometry.vector(getInLook()), outFrame.view(frontSide)));
	}

	public Vector getOutOffset(Direction outDirection)
	{
		return getOutOffset(PortalFrame.canonical(outDirection));
	}

	public Vector getOutOffset(PortalFrame outFrame)
	{
		return BukkitGeometry.bukkit(inFrame.transformVector(BukkitGeometry.vector(getInOffset()), outFrame.view(frontSide)));
	}

	public Vector getOutPoint(PortalFrame outFrame, Vector outOrigin)
	{
		return BukkitGeometry.bukkit(inFrame.transformPoint(BukkitGeometry.vector(inPoint), BukkitGeometry.vector(inOrigin), BukkitGeometry.vector(outOrigin), outFrame.view(frontSide)));
	}

	public PortalFrame getInFrame()
	{
		return inFrame;
	}

	public Vector getInVelocity()
	{
		return inVelocity;
	}

	public Vector getInLook()
	{
		return inLook;
	}

	public Vector getInOffset()
	{
		return inPoint.clone().subtract(inOrigin);
	}

	public Vector getInOrigin()
	{
		return inOrigin;
	}

	public Vector getInPoint()
	{
		return inPoint;
	}

	public boolean isFrontSide()
	{
		return frontSide;
	}

	/** The local portal this crossing entered, or null for remote arrivals and synthetic crossings. */
	public UUID getSourcePortalId()
	{
		return sourcePortalId;
	}

	public Object getObject()
	{
		return object;
	}

	public TraversableType getType()
	{
		return type;
	}
}
