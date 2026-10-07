package art.arcane.optics.stream;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateBuilder;
import art.arcane.optics.plate.ViewPlateKey;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

final class SessionPortal {
    static final String BLACKOUT = "minecraft:black_concrete";

    final UUID id;
    final int offsetX;
    volatile ViewPlate<String> plate;
    volatile ViewPlate<String> standby;
    volatile boolean refused;
    volatile boolean geometryAvailable = true;
    volatile long geometryRevision = 1L;
    volatile boolean mirror;
    volatile boolean frontSide;
    volatile int recursionDepth;
    volatile double depthBlocks = 24.0D;
    volatile double lateralBlocks = 8.0D;

    SessionPortal(String name, int offsetX) {
        this.id = UUID.nameUUIDFromBytes(name.getBytes());
        this.offsetX = offsetX;
    }

    ViewPlate<String> build(SessionWorld world) {
        return build(world, 0L);
    }

    ViewPlate<String> build(SessionWorld world, long targetIdentity) {
        ApertureCells geometry = new ApertureCells();
        geometry.setArea(new Box(10 + offsetX, 12.999 + offsetX, 66, 68.999, 20, 20.999));
        ViewPlateKey key = new ViewPlateKey(id, world, false, 0, targetIdentity);
        ViewPlateBuilder.Request<String, String, ContentView<String, String>> request =
            new ViewPlateBuilder.Request<String, String, ContentView<String, String>>(key, geometry, world,
                Frame.canonical(Face.S), Frame.canonical(Face.N), 11.4995 + offsetX, 67.4995, 20.5005,
                200.4995 + offsetX * 3, 67.4995, 200.4995, false, 0, depthBlocks, lateralBlocks, 0.75D, true, SessionWorld.AIR, LodPolicy.NONE, false,
                0L, 0L, 0L, SessionWorld.BLOCKS);
        return ViewPlateBuilder.build(request);
    }

    ApertureDescriptor geometry(SessionPalette palette) {
        boolean[] open = new boolean[9];
        Arrays.fill(open, true);
        return new ApertureDescriptor(10 + offsetX, 66, 20, Face.S.ordinal(), frontSide, 0, mirror, 3, 3,
            ApertureDescriptor.apertureMask(3, 3, open), 2.0F, 0.75F, 0.2F, 24, recursionDepth, ApertureDescriptor.BLACKOUT_SHELL,
            palette.id(BLACKOUT), ApertureDescriptor.MASK_AIR_PROJECT, 0, 0, 0, 0.0D, 0, 0L, List.of());
    }
}
