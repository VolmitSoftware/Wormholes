package art.arcane.wormholes.modded.client;

import art.arcane.optics.client.ClientCellRules;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.client.ClientSweep;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.Objects;

public final class ClientPortal {
    private final int portalKey;
    private final LongArrayList pendingEnters;
    private final IntOpenHashSet touchedBricks;
    private ApertureDescriptor geometry;
    private int geometryRevision;
    private ClientPlate plate;
    private ClientPortalContent content;
    private ClientSweep sweep;
    private ClientCellRules.Policy policy;
    private boolean contentDirty;
    private double hysteresis;

    public ClientPortal(int portalKey, ApertureDescriptor geometry, int geometryRevision, double hysteresis) {
        this.portalKey = portalKey;
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.geometryRevision = geometryRevision;
        this.hysteresis = hysteresis;
        this.pendingEnters = new LongArrayList();
        this.touchedBricks = new IntOpenHashSet();
    }

    public int portalKey() {
        return portalKey;
    }

    public ApertureDescriptor geometry() {
        return geometry;
    }

    public int geometryRevision() {
        return geometryRevision;
    }

    public ClientPlate plate() {
        return plate;
    }

    public ClientPortalContent content() {
        return content;
    }

    public boolean nested() {
        return geometry.parentPortalKey() != 0;
    }

    public ClientSweep sweep() {
        return sweep;
    }

    public ClientCellRules.Policy policy() {
        return policy;
    }

    public boolean ready() {
        return content != null && sweep != null;
    }

    public boolean contentDirty() {
        return contentDirty;
    }

    public void contentClean() {
        contentDirty = false;
        touchedBricks.clear();
    }

    public void markDirty() {
        contentDirty = true;
    }

    public LongArrayList pendingEnters() {
        return pendingEnters;
    }

    public IntOpenHashSet touchedBricks() {
        return touchedBricks;
    }

    public void geometry(ApertureDescriptor next, int revision) {
        Objects.requireNonNull(next, "next");
        boolean changed = !geometry.equals(next);
        geometry = next;
        geometryRevision = revision;
        if (!changed) {
            return;
        }
        if (content != null) {
            policy = ClientCellRules.Policy.of(geometry, content.backingState());
            if (sweep != null) {
                sweep.reconfigure(geometry, content.cells());
            }
            contentDirty = true;
        }
    }

    public void plate(ClientPlate next) {
        Objects.requireNonNull(next, "next");
        plate = next;
        content(next);
    }

    public void patch(ClientPlate next, IntArrayList bricks) {
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(bricks, "bricks");
        if (content != plate || sweep == null) {
            plate(next);
            return;
        }
        plate = next;
        content = next;
        touchedBricks.addAll(bricks);
    }

    public void content(ClientPortalContent next) {
        Objects.requireNonNull(next, "next");
        content = next;
        policy = ClientCellRules.Policy.of(geometry, next.backingState());
        if (sweep == null) {
            sweep = new ClientSweep(geometry, next.cells(), hysteresis);
        } else if (!sweep.bounds().equals(next.cells())) {
            sweep.reconfigure(geometry, next.cells());
        }
        contentDirty = true;
    }

    public void clearContent() {
        plate = null;
        content = null;
        sweep = null;
        policy = null;
        contentDirty = false;
        pendingEnters.clear();
        touchedBricks.clear();
    }

    public void hysteresis(double value) {
        hysteresis = value;
    }

    public double eyeDistance() {
        return sweep == null ? Double.MAX_VALUE : Math.abs(sweep.eyeDot());
    }
}
