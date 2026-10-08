package art.arcane.wormholes.portal;

import art.arcane.wormholes.util.BukkitGeometry;

import java.util.UUID;

import art.arcane.wormholes.network.WireTraversive;

import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import art.arcane.optics.math.Face;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Angles;

public class Traversive
{
	private final Object object;
	private final TraversableType type;
	private final Frame inFrame;
	private final Vector inOrigin;
	private final Vector inPoint;
	private final Vector inVelocity;
	private final Vector inLook;
	private final boolean frontSide;
	private final UUID sourcePortalId;
	private final Angles.Look inRotation;

	public Traversive(Object o, TraversableType type, Face inDirection, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook)
	{
		this(o, type, Frame.canonical(inDirection), inOrigin, inPoint, inVelocity, inLook, true);
	}

	public Traversive(Object o, TraversableType type, Frame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook)
	{
		this(o, type, inFrame, inOrigin, inPoint, inVelocity, inLook, true);
	}

	public Traversive(Object o, TraversableType type, Frame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook, boolean frontSide)
	{
		this(o, type, inFrame, inOrigin, inPoint, inVelocity, inLook, frontSide, null);
	}

	public Traversive(Object o, TraversableType type, Frame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook, boolean frontSide, UUID sourcePortalId)
	{
		this(o, type, inFrame, inOrigin, inPoint, inVelocity, inLook, frontSide, sourcePortalId, Angles.look(inLook.getX(), inLook.getY(), inLook.getZ()));
	}

	public Traversive(Object o, TraversableType type, Frame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook, boolean frontSide, UUID sourcePortalId, Angles.Look inRotation)
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
		this.inRotation = inRotation;
	}

	public Traversive(Entity entity, Face inDirection, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook)
	{
		this(entity, TraversableType.ENTITY, inDirection, inOrigin, inPoint, inVelocity, inLook);
	}

	public Traversive(Entity entity, Frame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook)
	{
		this(entity, TraversableType.ENTITY, inFrame, inOrigin, inPoint, inVelocity, inLook);
	}

	public Traversive(Entity entity, Frame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook, boolean frontSide)
	{
		this(entity, TraversableType.ENTITY, inFrame, inOrigin, inPoint, inVelocity, inLook, frontSide);
	}

	public Traversive(Entity entity, Frame inFrame, Vector inOrigin, Vector inPoint, Vector inVelocity, Vector inLook, boolean frontSide, UUID sourcePortalId)
	{
		this(entity, TraversableType.ENTITY, inFrame, inOrigin, inPoint, inVelocity, inLook, frontSide, sourcePortalId);
	}

    public static WireTraversive toWire(Traversive t) {
        return WireTraversive.fromCrossing(t.crossing());
    }

    public static Traversive fromWire(WireTraversive wire, Object object) {
        Frame frame = new Frame(Face.valueOf(wire.frameNormal()), Face.valueOf(wire.frameRight()), Face.valueOf(wire.frameUp()));
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
		return new Traversive(member, TraversableType.ENTITY, inFrame, inOrigin, memberPoint, inVelocity, inLook, frontSide, sourcePortalId, inRotation);
	}

    public PlaneCrossing crossing() {
        return new PlaneCrossing(inFrame, BukkitGeometry.vector(inOrigin), BukkitGeometry.vector(inPoint),
            BukkitGeometry.vector(inVelocity), BukkitGeometry.vector(inLook), frontSide);
    }

	public Vector getOutVelocity(Face outDirection)
	{
		return getOutVelocity(Frame.canonical(outDirection));
	}

	public Vector getOutVelocity(Frame outFrame)
	{
		return BukkitGeometry.bukkit(toward(outFrame).vector(BukkitGeometry.vector(getInVelocity())));
	}

	public Vector getOutLook(Face outDirection)
	{
		return getOutLook(Frame.canonical(outDirection));
	}

	public Vector getOutLook(Frame outFrame)
	{
		return BukkitGeometry.bukkit(toward(outFrame).vector(BukkitGeometry.vector(getInLook())));
	}

	public Vector getOutOffset(Face outDirection)
	{
		return getOutOffset(Frame.canonical(outDirection));
	}

	public Vector getOutOffset(Frame outFrame)
	{
		return BukkitGeometry.bukkit(toward(outFrame).vector(BukkitGeometry.vector(getInOffset())));
	}

	public Vector getOutPoint(Frame outFrame, Vector outOrigin)
	{
		return BukkitGeometry.bukkit(OpticTransform.between(inFrame, BukkitGeometry.vector(inOrigin), outFrame.view(frontSide),
			BukkitGeometry.vector(outOrigin)).point(BukkitGeometry.vector(inPoint)));
	}

	public Frame getInFrame()
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

	public Angles.Look getInRotation()
	{
		return inRotation;
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

	private OpticTransform toward(Frame outFrame)
	{
		return OpticTransform.of(AxisPermutation.between(inFrame, outFrame.view(frontSide)), 0.0D, 0.0D, 0.0D);
	}
}
