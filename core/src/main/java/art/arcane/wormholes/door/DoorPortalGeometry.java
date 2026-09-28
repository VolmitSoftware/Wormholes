package art.arcane.wormholes.door;

import art.arcane.wormholes.util.Direction;
import art.arcane.wormholes.util.Axis;
import java.util.Objects;

public final class DoorPortalGeometry {
	private static final float PORTAL_INSET = 0.0625F;
	private static final float PORTAL_RECESS = (float) DoorwayPlane.PORTAL_RECESS;
	private static final float PORTAL_WIDTH = 1.0F - PORTAL_INSET;
	private static final float PORTAL_HEIGHT = 2.0F - (PORTAL_INSET * 2.0F);
	private static final float PORTAL_THICKNESS = (float) DoorwayPlane.PORTAL_THICKNESS;
	private static final float CONTACT_PORTAL_THICKNESS =
		(float) DoorwayPlane.TRAPDOOR_PLATE_THICKNESS + 0.02F;
	private static final float PORTAL_OVERLAY_THICKNESS = 0.15F;

    private DoorPortalGeometry() {
    }
	/** The surface normal of the visible panel: flat and upward for a trapdoor. */
	public static Direction panelFace(DoorwayPlane plane)
	{
		Objects.requireNonNull(plane, "plane");
		return plane.horizontal() ? Direction.U : plane.facing();
	}

	/**
	 * Panel geometry for one plane. A trapdoor's veil is a flat one-by-one slab
	 * lying in the plate plane, so the hinge - a hinged-door concept - is ignored.
	 */
	public static PortalPlaneGeometry planeGeometry(DoorwayPlane plane, DoorHinge hinge)
	{
		Objects.requireNonNull(plane, "plane");
		if(!plane.horizontal())
		{
			PortalPlaneGeometry vertical = geometry(plane.facing(), hinge);
			return plane.contactSurface() ? contactGeometry(vertical, plane.facing()) : vertical;
		}
		float thickness = plane.contactSurface() ? CONTACT_PORTAL_THICKNESS : PORTAL_THICKNESS;
		return new PortalPlaneGeometry(
			-PORTAL_WIDTH / 2.0F,
			(float) (plane.planeY() - plane.blockY()) - (thickness / 2.0F),
			-PORTAL_WIDTH / 2.0F,
			PORTAL_WIDTH,
			thickness,
			PORTAL_WIDTH);
	}

	private static PortalPlaneGeometry contactGeometry(PortalPlaneGeometry geometry, Direction facing)
	{
		return switch(facing)
		{
			case N, S -> new PortalPlaneGeometry(
				geometry.translationX(),
				geometry.translationY(),
				geometry.translationZ() + ((geometry.scaleZ() - CONTACT_PORTAL_THICKNESS) / 2.0F),
				geometry.scaleX(),
				geometry.scaleY(),
				CONTACT_PORTAL_THICKNESS);
			case E, W -> new PortalPlaneGeometry(
				geometry.translationX() + ((geometry.scaleX() - CONTACT_PORTAL_THICKNESS) / 2.0F),
				geometry.translationY(),
				geometry.translationZ(),
				CONTACT_PORTAL_THICKNESS,
				geometry.scaleY(),
				geometry.scaleZ());
			default -> throw new IllegalArgumentException("Door portal facing must be cardinal: " + facing);
		};
	}

	public static PortalPlaneGeometry geometry(Direction facing, DoorHinge hinge)
	{
		Objects.requireNonNull(facing, "facing");
		Objects.requireNonNull(hinge, "hinge");
		float lateralTranslation = lateralTranslation(facing, hinge);
		return switch(facing)
		{
			case N -> new PortalPlaneGeometry(
				lateralTranslation,
				PORTAL_INSET,
				0.5F - PORTAL_RECESS - PORTAL_THICKNESS,
				PORTAL_WIDTH,
				PORTAL_HEIGHT,
				PORTAL_THICKNESS);
			case S -> new PortalPlaneGeometry(
				lateralTranslation,
				PORTAL_INSET,
				-0.5F + PORTAL_RECESS,
				PORTAL_WIDTH,
				PORTAL_HEIGHT,
				PORTAL_THICKNESS);
			case E -> new PortalPlaneGeometry(
				-0.5F + PORTAL_RECESS,
				PORTAL_INSET,
				lateralTranslation,
				PORTAL_THICKNESS,
				PORTAL_HEIGHT,
				PORTAL_WIDTH);
			case W -> new PortalPlaneGeometry(
				0.5F - PORTAL_RECESS - PORTAL_THICKNESS,
				PORTAL_INSET,
				lateralTranslation,
				PORTAL_THICKNESS,
				PORTAL_HEIGHT,
				PORTAL_WIDTH);
			default -> throw new IllegalArgumentException("Door portal facing must be cardinal: " + facing);
		};
	}

	public static PortalPlaneGeometry overlayGeometry(PortalPlaneGeometry backing, Direction facing)
	{
		Objects.requireNonNull(backing, "backing");
		Objects.requireNonNull(facing, "facing");
		return switch(facing)
		{
			case N, S -> overlayAlongZ(backing);
			case E, W -> overlayAlongX(backing);
			case U, D -> overlayAlongY(backing);
		};
	}

	private static PortalPlaneGeometry overlayAlongX(PortalPlaneGeometry backing)
	{
		float thickness = Math.max(PORTAL_OVERLAY_THICKNESS, backing.scaleX() + 0.02F);
		return new PortalPlaneGeometry(
			backing.translationX() + (backing.scaleX() / 2.0F) - (thickness / 2.0F),
			backing.translationY(),
			backing.translationZ(),
			thickness,
			backing.scaleY(),
			backing.scaleZ());
	}

	private static PortalPlaneGeometry overlayAlongY(PortalPlaneGeometry backing)
	{
		float thickness = Math.max(PORTAL_OVERLAY_THICKNESS, backing.scaleY() + 0.02F);
		return new PortalPlaneGeometry(
			backing.translationX(),
			backing.translationY() + (backing.scaleY() / 2.0F) - (thickness / 2.0F),
			backing.translationZ(),
			backing.scaleX(),
			thickness,
			backing.scaleZ());
	}

	private static PortalPlaneGeometry overlayAlongZ(PortalPlaneGeometry backing)
	{
		float thickness = Math.max(PORTAL_OVERLAY_THICKNESS, backing.scaleZ() + 0.02F);
		return new PortalPlaneGeometry(
			backing.translationX(),
			backing.translationY(),
			backing.translationZ() + (backing.scaleZ() / 2.0F) - (thickness / 2.0F),
			backing.scaleX(),
			backing.scaleY(),
			thickness);
	}

	/** A nether portal block only ever lies on X or Z, so a flat panel picks X. */
	public static Axis overlayAxis(Direction facing)
	{
		Objects.requireNonNull(facing, "facing");
		return switch(facing)
		{
			case N, S, U, D -> Axis.X;
			case E, W -> Axis.Z;
		};
	}

	private static float lateralTranslation(Direction facing, DoorHinge hinge)
	{
		int hingeSign = hinge == DoorHinge.LEFT ? 1 : -1;
		int farSideSign = switch(facing)
		{
			case N, S -> -facing.z() * hingeSign;
			case E, W -> facing.x() * hingeSign;
			default -> throw new IllegalArgumentException("Door portal facing must be cardinal: " + facing);
		};
		return farSideSign > 0 ? -0.5F + PORTAL_INSET : -0.5F;
	}

}
