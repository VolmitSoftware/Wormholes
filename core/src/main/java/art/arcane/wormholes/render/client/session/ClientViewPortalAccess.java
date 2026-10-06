package art.arcane.wormholes.render.client.session;

import java.util.List;
import java.util.UUID;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.plate.ViewPlate;

public interface ClientViewPortalAccess<P, B> {
    void interested(P observer, List<UUID> out);

    long geometryRevision(P observer, UUID portal);

    ApertureDescriptor geometry(P observer, UUID portal, SessionPalette palette);

    ViewPlate<B> plate(P observer, UUID portal, boolean firstAttendance);

    default int meshDistanceBlocks(P observer) {
        return 0;
    }

    default Vec3 meshEye(P observer) {
        return null;
    }

    default void prepareNested(P observer, UUID context, UUID parentContext, UUID portal) {
    }

    default void releaseNested(P observer, UUID context) {
    }

    default Vec3 nestedEye(P observer, UUID context) {
        return null;
    }

    default WorldChangeTracker meshChanges(P observer) {
        return null;
    }

    default boolean localMeshWorld(P observer, UUID context) {
        return false;
    }

    default ViewPlate<B> meshSection(P observer, UUID portal, PlateBox clip, int distance) {
        return null;
    }

    default ViewPlate<B> nestedMeshSection(P observer, UUID parent, UUID child, PlateBox clip, int distance) {
        return null;
    }

    default boolean meshSectionQueued(P observer, UUID portal, PlateBox clip) {
        return false;
    }

    default boolean nestedMeshSectionQueued(P observer, UUID parent, UUID child, PlateBox clip) {
        return meshSectionQueued(observer, child, clip);
    }

    default SectionBiomes meshBiomes(P observer, UUID portal, ViewPlate<B> plate) {
        return SectionBiomes.NONE;
    }

    boolean refused(P observer, UUID portal);

    ViewPlate<B> standbyPlate(P observer, UUID portal);

    BrickLightSource lightBaseline(P observer, UUID portal, ViewPlate<B> plate);

    void releaseVanilla(P observer, UUID portal);

    void nested(P observer, UUID parent, ApertureDescriptor parentGeometry, List<UUID> out);

    long nestedGeometryRevision(P observer, UUID parent, UUID child);

    ApertureDescriptor nestedGeometry(P observer, UUID parent, UUID child, SessionPalette palette);

    ViewPlate<B> nestedPlate(P observer, UUID parent, UUID child);

    void effects(P observer, List<UUID> out);

    long effectGeometryRevision(P observer, UUID portal);

    ApertureDescriptor effectGeometry(P observer, UUID portal, SessionPalette palette);
}
