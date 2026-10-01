package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.client.ClientSpace;
import art.arcane.wormholes.render.plate.PlateBox;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

import java.util.Objects;

public final class ClientNestedContent implements ClientPortalContent {
    private final ClientPlate plate;
    private final ClientSpace space;
    private final ClientPalette palette;
    private final ClientStateReflector reflector;
    private final Int2IntOpenHashMap reflectedIds;
    private final int[] cell;
    private final double[] scratch;

    public ClientNestedContent(ClientPlate plate, ClientSpace space, ClientPalette palette) {
        this.plate = Objects.requireNonNull(plate, "plate");
        this.space = Objects.requireNonNull(space, "space");
        this.palette = Objects.requireNonNull(palette, "palette");
        this.reflector = new ClientStateReflector(space.reflections());
        this.reflectedIds = new Int2IntOpenHashMap(64);
        this.reflectedIds.defaultReturnValue(-1);
        this.cell = new int[3];
        this.scratch = new double[3];
    }

    public ClientPlate plate() {
        return plate;
    }

    public ClientSpace space() {
        return space;
    }

    @Override
    public PlateBox cells() {
        return plate.cells();
    }

    @Override
    public int backingState() {
        return reflect(plate.backingState());
    }

    @Override
    public int paletteIdAt(int x, int y, int z) {
        space.contentCell(x, y, z, cell, scratch);
        return reflect(plate.paletteIdAt(cell[0], cell[1], cell[2]));
    }

    @Override
    public BlockEntitySample blockEntityAt(int x, int y, int z) {
        if (!reflector.identity()) {
            return null;
        }
        space.contentCell(x, y, z, cell, scratch);
        return plate.blockEntityAt(cell[0], cell[1], cell[2]);
    }

    private int reflect(int paletteId) {
        if (reflector.identity() || paletteId < ClientViewProtocol.RESERVED_PALETTE_IDS) {
            return paletteId;
        }
        int known = reflectedIds.get(paletteId);
        if (known >= 0) {
            return known;
        }
        int reflected = palette.localId(reflector.reflect(palette.state(paletteId)));
        reflectedIds.put(paletteId, reflected);
        return reflected;
    }
}
