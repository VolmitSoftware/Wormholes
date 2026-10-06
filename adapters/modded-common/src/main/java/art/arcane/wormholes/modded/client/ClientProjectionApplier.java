package art.arcane.wormholes.modded.client;

import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.client.ClientCellRules;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

public final class ClientProjectionApplier {
    private final ClientViewSurface surface;
    private final ProjectionOverlay overlay;
    private final ClientPalette palette;
    private long appliedCells;
    private long revertedCells;
    private long deferredCells;
    private long unknownCells;
    private long writes;

    public ClientProjectionApplier(ClientViewSurface surface, ProjectionOverlay overlay, ClientPalette palette) {
        this.surface = Objects.requireNonNull(surface, "surface");
        this.overlay = Objects.requireNonNull(overlay, "overlay");
        this.palette = Objects.requireNonNull(palette, "palette");
    }

    public boolean enter(long key, int portalKey, ClientPortalContent plate, ClientCellRules.Policy policy, boolean shell) {
        int x = CellKeys.unpackX(key);
        int y = CellKeys.unpackY(key);
        int z = CellKeys.unpackZ(key);
        ProjectionOverlay.Entry entry = overlay.get(key);
        if (entry != null && entry.portalKey() != portalKey) {
            return false;
        }
        BlockState shadow = entry != null && !entry.pending() ? entry.shadow() : surface.state(x, y, z);
        int paletteId = plate.paletteIdAt(x, y, z);
        boolean contentOccluding = paletteId >= ViewStreamLimits.RESERVED_PALETTE_IDS && palette.state(paletteId).canOcclude();
        boolean shadowAir = shadow != null && shadow.isAir();
        int resolved = ClientCellRules.evaluate(shell, paletteId, contentOccluding, shadowAir, policy);
        if (resolved == ClientCellRules.KEEP_REAL) {
            if (entry != null) {
                exit(key, portalKey);
            }
            return false;
        }
        if (resolved >= ViewStreamLimits.RESERVED_PALETTE_IDS && !palette.known(resolved)) {
            unknownCells++;
        }
        BlockState projected = palette.state(resolved);
        BlockEntitySample sample = projected.hasBlockEntity() ? plate.blockEntityAt(x, y, z) : null;
        if (shadow == null) {
            overlay.enter(key, projected, null, portalKey, true).blockEntity(sample);
            deferredCells++;
            return true;
        }
        BlockState current = entry == null || entry.pending() ? shadow : entry.projected();
        if (current != projected) {
            write(x, y, z, projected);
            if (sample != null) {
                surface.blockEntity(x, y, z, sample);
            }
        }
        overlay.enter(key, projected, shadow, portalKey, false).blockEntity(sample);
        appliedCells++;
        return true;
    }

    public boolean exit(long key, int portalKey) {
        ProjectionOverlay.Entry entry = overlay.get(key);
        if (entry == null || entry.portalKey() != portalKey) {
            return false;
        }
        overlay.exit(key);
        BlockState shadow = entry.shadow();
        if (entry.pending() || shadow == null) {
            return true;
        }
        if (shadow != entry.projected()) {
            write(CellKeys.unpackX(key), CellKeys.unpackY(key), CellKeys.unpackZ(key), shadow);
        }
        revertedCells++;
        return true;
    }

    public int revertAll() {
        int reverted = 0;
        for (long key : overlay.keys()) {
            ProjectionOverlay.Entry entry = overlay.get(key);
            if (entry != null && exit(key, entry.portalKey())) {
                reverted++;
            }
        }
        overlay.clear();
        return reverted;
    }

    public int revertPortal(int portalKey) {
        int reverted = 0;
        for (long key : overlay.keysOf(portalKey)) {
            if (exit(key, portalKey)) {
                reverted++;
            }
        }
        return reverted;
    }

    public void flush() {
        surface.flush();
    }

    public ProjectionOverlay overlay() {
        return overlay;
    }

    public long appliedCells() {
        return appliedCells;
    }

    public long revertedCells() {
        return revertedCells;
    }

    public long deferredCells() {
        return deferredCells;
    }

    public long unknownCells() {
        return unknownCells;
    }

    public long writes() {
        return writes;
    }

    private void write(int x, int y, int z, BlockState state) {
        overlay.writing(true);
        try {
            surface.write(x, y, z, state);
        } finally {
            overlay.writing(false);
        }
        writes++;
    }
}
