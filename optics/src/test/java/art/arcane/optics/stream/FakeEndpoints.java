package art.arcane.optics.stream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.ViewPlateKey;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.plate.ViewPlate;

final class FakeEndpoints implements ViewStreamEndpoints<String, String> {
    final Map<UUID, SessionPortal> portals = new LinkedHashMap<UUID, SessionPortal>();
    final List<UUID> interest = new ArrayList<UUID>();
    final Map<UUID, List<SessionPortal>> nested = new HashMap<UUID, List<SessionPortal>>();
    final Map<UUID, SessionPortal> effectPortals = new LinkedHashMap<UUID, SessionPortal>();
    final List<UUID> effects = new ArrayList<UUID>();
    final Set<UUID> plateRequested = new HashSet<UUID>();
    final List<UUID> urgentRequests = new ArrayList<UUID>();
    final Set<UUID> refusalChecked = new HashSet<UUID>();
    final List<String> events;
    final Map<UUID, UUID> contexts = new HashMap<>();
    int meshDistance;
    WorldChangeTracker meshChanges;
    int meshCalls;
    final List<BlockBox> meshRequests = new ArrayList<BlockBox>();
    boolean meshReady = true;
    boolean meshQueued;
    boolean localWorld;
    final Set<BlockBox> unavailableMesh = new HashSet<BlockBox>();
    Vec3d eye = new Vec3d(11, 67, 15);
    final Map<BlockBox, ViewPlate<String>> meshPlates = new HashMap<BlockBox, ViewPlate<String>>();
    int standbyCalls;
    int geometryCalls;
    int nestedCalls;
    BrickLightSource light = BrickLightSource.NONE;

    FakeEndpoints(List<String> events) {
        this.events = events;
    }

    SessionPortal add(SessionPortal portal) {
        portals.put(portal.id, portal);
        interest.add(portal.id);
        return portal;
    }

    @Override
    public void interested(String observer, List<UUID> out) {
        for (int i = 0; i < interest.size(); i++) {
            out.add(interest.get(i));
        }
    }

    @Override
    public long geometryRevision(String observer, UUID portal) {
        return portals.get(portal).geometryRevision;
    }

    @Override
    public ApertureDescriptor geometry(String observer, UUID portal, SessionPalette palette) {
        geometryCalls++;
        SessionPortal known = portals.get(portal);
        return known.geometryAvailable ? known.geometry(palette) : null;
    }

    @Override
    public ViewPlate<String> plate(String observer, UUID portal, boolean firstAttendance) {
        plateRequested.add(portal);
        if (firstAttendance) {
            urgentRequests.add(portal);
        }
        return portals.get(portal).plate;
    }

    @Override
    public int meshDistanceBlocks(String observer) {
        return meshDistance;
    }

    @Override
    public Vec3d meshEye(String observer) {
        return eye;
    }

    @Override
    public WorldChangeTracker meshChanges(String observer) {
        return meshChanges;
    }

    @Override
    public boolean localMeshWorld(String observer, UUID context) {
        return localWorld;
    }

    @Override
    public ViewPlate<String> meshSection(String observer, UUID portal, BlockBox clip, int distance) {
        meshCalls++;
        meshRequests.add(clip);
        if (!meshReady || unavailableMesh.contains(clip)) {
            return null;
        }
        return meshPlates.computeIfAbsent(clip, box -> PlateTestFixtures.empty(new ViewPlateKey(portal, box, false, 0, 0), box));
    }

    @Override
    public ViewPlate<String> nestedMeshSection(String observer, UUID parent, UUID child, BlockBox clip, int distance) {
        return meshSection(observer, child, clip, distance);
    }

    @Override
    public boolean meshSectionQueued(String observer, UUID portal, BlockBox clip) {
        return meshQueued;
    }

    @Override
    public boolean refused(String observer, UUID portal) {
        refusalChecked.add(portal);
        return portals.get(portal).refused;
    }

    @Override
    public ViewPlate<String> standbyPlate(String observer, UUID portal) {
        standbyCalls++;
        return portals.get(portal).standby;
    }

    @Override
    public BrickLightSource lightBaseline(String observer, UUID portal, ViewPlate<String> plate) {
        return light;
    }

    @Override
    public void releaseVanilla(String observer, UUID portal) {
        events.add("release " + portal);
    }

    @Override
    public void prepareNested(String observer, UUID context, UUID parentContext, UUID portal) {
        contexts.put(context, portal);
    }

    @Override
    public void releaseNested(String observer, UUID context) {
        contexts.remove(context);
    }

    @Override
    public Vec3d nestedEye(String observer, UUID context) {
        return eye;
    }

    @Override
    public void nested(String observer, UUID parent, ApertureDescriptor parentGeometry, List<UUID> out) {
        nestedCalls++;
        List<SessionPortal> children = nested.get(contexts.getOrDefault(parent, parent));
        if (children == null) {
            return;
        }
        for (SessionPortal child : children) {
            out.add(child.id);
        }
    }

    @Override
    public long nestedGeometryRevision(String observer, UUID parent, UUID child) {
        return child(parent, child).geometryRevision;
    }

    @Override
    public ApertureDescriptor nestedGeometry(String observer, UUID parent, UUID child, SessionPalette palette) {
        SessionPortal known = child(parent, child);
        return known.geometryAvailable ? known.geometry(palette) : null;
    }

    @Override
    public ViewPlate<String> nestedPlate(String observer, UUID parent, UUID child) {
        return child(parent, child).plate;
    }

    SessionPortal effect(SessionPortal portal) {
        effectPortals.put(portal.id, portal);
        effects.add(portal.id);
        return portal;
    }

    @Override
    public void effects(String observer, List<UUID> out) {
        for (int i = 0; i < effects.size(); i++) {
            out.add(effects.get(i));
        }
    }

    @Override
    public long effectGeometryRevision(String observer, UUID portal) {
        SessionPortal known = effectPortals.get(portal);
        return known == null ? 0L : known.geometryRevision;
    }

    @Override
    public ApertureDescriptor effectGeometry(String observer, UUID portal, SessionPalette palette) {
        SessionPortal known = effectPortals.get(portal);
        return known == null || !known.geometryAvailable ? null : known.geometry(palette);
    }

    private SessionPortal child(UUID parent, UUID child) {
        for (SessionPortal candidate : nested.get(contexts.getOrDefault(parent, parent))) {
            if (candidate.id.equals(child)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no nested portal " + child + " under " + parent);
    }
}
