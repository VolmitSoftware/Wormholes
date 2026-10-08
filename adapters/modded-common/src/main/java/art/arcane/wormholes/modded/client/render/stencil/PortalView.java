package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.shape.ShapeMesh;
import art.arcane.wormholes.modded.client.render.PortalApertureMesh;
import art.arcane.wormholes.modded.client.render.PortalGpuMesh;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.Objects;

public final class PortalView implements AutoCloseable {
    private final Kind kind;
    private final Object key;
    private final ClientLevel source;
    private final ClientLevel destination;
    private final PortalSurface surface;
    private final Similarity toDestination;
    private final int recursion;
    private PortalSurface exit;
    private PortalGpuMesh mesh;
    private int meshSubdivisions;
    private long failedUntil;

    PortalView(Kind kind, Object key, ClientLevel source, ClientLevel destination, PortalSurface surface, Similarity toDestination, int recursion) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.key = Objects.requireNonNull(key, "key");
        this.source = Objects.requireNonNull(source, "source");
        this.destination = Objects.requireNonNull(destination, "destination");
        this.surface = Objects.requireNonNull(surface, "surface");
        this.toDestination = Objects.requireNonNull(toDestination, "toDestination");
        this.recursion = Math.max(1, recursion);
    }

    public Kind kind() {
        return kind;
    }

    public Object key() {
        return key;
    }

    public ClientLevel source() {
        return source;
    }

    public ClientLevel destination() {
        return destination;
    }

    public PortalSurface surface() {
        return surface;
    }

    public Similarity toDestination() {
        return toDestination;
    }

    public PortalSurface exit() {
        if (exit == null) {
            exit = surface.through(toDestination);
        }
        return exit;
    }

    public int recursion() {
        return recursion;
    }

    public boolean shaped() {
        return surface.aperture().hasShape();
    }

    public boolean failing(long now) {
        return now < failedUntil;
    }

    public void failed(long until) {
        failedUntil = until;
    }

    public PortalGpuMesh mesh(int requestedSubdivisions) {
        ApertureDescriptor geometry = surface.geometry();
        AperturePolygon aperture = surface.aperture();
        if (!aperture.hasShape()) {
            if (mesh == null) {
                mesh = PortalApertureMesh.quads(aperture, geometry);
            }
            return mesh;
        }
        int subdivisions = PortalApertureMesh.subdivisions(requestedSubdivisions, geometry.apertureWidth(), geometry.apertureHeight());
        if (mesh != null && meshSubdivisions == subdivisions) {
            return mesh;
        }
        close();
        ShapeMesh shape = aperture.planeShape().mesh(subdivisions, PortalApertureMesh.renderMask(geometry, aperture.planeShape()));
        mesh = PortalApertureMesh.shaped(shape, aperture, geometry);
        meshSubdivisions = subdivisions;
        return mesh;
    }

    @Override
    public void close() {
        if (mesh != null) {
            mesh.close();
            mesh = null;
        }
    }

    public enum Kind {
        ARM,
        MIRROR,
        RETURN
    }
}
