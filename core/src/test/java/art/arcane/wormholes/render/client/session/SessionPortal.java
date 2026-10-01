package art.arcane.wormholes.render.client.session;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.plate.ViewPlateBuilder;
import art.arcane.wormholes.render.plate.ViewPlateKey;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

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
        PortalGeometry geometry = new PortalGeometry();
        geometry.setArea(new AxisAlignedBB(10 + offsetX, 12.999 + offsetX, 66, 68.999, 20, 20.999));
        ViewPlateKey key = new ViewPlateKey(id, world, false, 0, targetIdentity);
        ViewPlateBuilder.Request<String, String, ProjectionContentView<String, String>> request =
            new ViewPlateBuilder.Request<String, String, ProjectionContentView<String, String>>(key, geometry, world,
                PortalFrame.canonical(Direction.S), PortalFrame.canonical(Direction.N), 11.4995 + offsetX, 67.4995, 20.5005,
                200.4995 + offsetX * 3, 67.4995, 200.4995, false, 0, depthBlocks, lateralBlocks, 0.75D, true, SessionWorld.AIR, LodPolicy.NONE, false,
                0L, 0L, 0L, SessionWorld.BLOCKS);
        return ViewPlateBuilder.build(request);
    }

    ClientPortalGeometry geometry(SessionPalette palette) {
        boolean[] open = new boolean[9];
        Arrays.fill(open, true);
        return new ClientPortalGeometry(10 + offsetX, 66, 20, Direction.S.ordinal(), false, 0, mirror, 3, 3,
            ClientPortalGeometry.apertureMask(3, 3, open), 2.0F, 0.75F, 0.2F, 24, recursionDepth, ClientPortalGeometry.BLACKOUT_SHELL,
            palette.id(BLACKOUT), ClientPortalGeometry.MASK_AIR_PROJECT, 0, 0, ClientPortalGeometry.KIND_FRAME, 0, 0L, List.of());
    }
}
