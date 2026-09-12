package art.arcane.wormholes.door.view;

import art.arcane.wormholes.door.DoorProjectionState;
import art.arcane.wormholes.door.DoorVec3;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.door.RuntimeDoor;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.Objects;
import java.util.UUID;

/**
 * Projectable aperture for one placed Dimensional Door.
 *
 * <p>A hinged door contributes both blocks of its opening; a trapdoor contributes the single block
 * its plate sits in. The frame's normal is the direction a traveler passes through, so the
 * projection lines up with the surface the veil is already drawn on.</p>
 */
public final class DoorProjectionAdapter extends AbstractApertureFacade {
    private final RuntimeDoor door;
    private final World world;
    private final PortalStructure structure;

    private volatile DoorwayPlane plane;
    private volatile PortalFrame frame;
    private volatile Vector origin;

    public DoorProjectionAdapter(RuntimeDoor door, DoorwayPlane plane, World world) {
        this.door = Objects.requireNonNull(door, "door");
        this.world = Objects.requireNonNull(world, "world");
        structure = new PortalStructure();
        structure.setWorld(world);
        refresh(plane);
    }

    /** Re-reads the aperture after the door was observed again. */
    public void refresh(DoorwayPlane updated) {
        DoorwayPlane required = Objects.requireNonNull(updated, "updated");
        plane = required;
        frame = DoorApertureFrames.of(required);
        DoorVec3 center = required.center();
        origin = new Vector(center.x(), center.y(), center.z());
        structure.setArea(DoorApertureFrames.cells(world, required));
    }

    public PlacedDoorEndpoint endpoint() {
        return door.endpoint();
    }

    public DoorwayPlane plane() {
        return plane;
    }

    public DoorProjectionState projectionState() {
        return door.endpoint().projection();
    }

    @Override
    public PortalStructure getStructure() {
        return structure;
    }

    @Override
    public PortalFrame getFrame() {
        return frame;
    }

    @Override
    public Vector getOrigin() {
        return origin.clone();
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public UUID getId() {
        return door.endpoint().identity().itemId();
    }

    @Override
    public String getName() {
        return door.endpoint().identity().itemId().toString();
    }

    @Override
    public boolean isOpen() {
        return door.cycle().portalActive();
    }
}
