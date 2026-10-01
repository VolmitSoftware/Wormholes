package art.arcane.wormholes.render.client.session;

import java.util.List;
import java.util.UUID;

import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.plate.ViewPlate;

public interface ClientViewPortalAccess<P, B> {
    void interested(P observer, List<UUID> out);

    long geometryRevision(P observer, UUID portal);

    ClientPortalGeometry geometry(P observer, UUID portal, SessionPalette palette);

    ViewPlate<B> plate(P observer, UUID portal, boolean firstAttendance);

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
