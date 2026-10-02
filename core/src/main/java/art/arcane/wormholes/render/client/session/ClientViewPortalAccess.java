package art.arcane.wormholes.render.client.session;

import java.util.List;
import java.util.UUID;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.SectionBiomes;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.plate.ViewPlate;

public interface ClientViewPortalAccess<P, B> {
    void interested(P observer, List<UUID> out);

    long geometryRevision(P observer, UUID portal);

    ClientPortalGeometry geometry(P observer, UUID portal, SessionPalette palette);

    ViewPlate<B> plate(P observer, UUID portal, boolean firstAttendance);

    default int meshDistanceBlocks(P observer) {
        return 0;
    }

    default GeometryVector meshEye(P observer) {
        return null;
    }

    default void prepareNested(P observer, UUID context, UUID parentContext, UUID portal) {
    }

    default void releaseNested(P observer, UUID context) {
    }

    default GeometryVector nestedEye(P observer, UUID context) {
        return null;
    }

    default ProjectionWorldChangeTracker meshChanges(P observer) {
        return null;
    }

    default ViewPlate<B> meshSection(P observer, UUID portal, PlateBox clip, int distance) {
        return null;
    }

    default ViewPlate<B> nestedMeshSection(P observer, UUID parent, UUID child, PlateBox clip, int distance) {
        return null;
    }

    default SectionBiomes meshBiomes(P observer, UUID portal, ViewPlate<B> plate) {
        return SectionBiomes.NONE;
    }

    boolean refused(P observer, UUID portal);

    ViewPlate<B> standbyPlate(P observer, UUID portal);

    BrickLightSource lightBaseline(P observer, UUID portal, ViewPlate<B> plate);

    void releaseVanilla(P observer, UUID portal);

    void nested(P observer, UUID parent, ClientPortalGeometry parentGeometry, List<UUID> out);

    long nestedGeometryRevision(P observer, UUID parent, UUID child);

    ClientPortalGeometry nestedGeometry(P observer, UUID parent, UUID child, SessionPalette palette);

    ViewPlate<B> nestedPlate(P observer, UUID parent, UUID child);

    void effects(P observer, List<UUID> out);

    long effectGeometryRevision(P observer, UUID portal);

    ClientPortalGeometry effectGeometry(P observer, UUID portal, SessionPalette palette);
}
