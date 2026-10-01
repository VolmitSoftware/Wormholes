package art.arcane.wormholes.network.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.plate.ViewPlateBuilder;
import art.arcane.wormholes.render.plate.ViewPlateKey;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class DomePlates {
    static final double DEPTH_BLOCKS = 64.0D;
    static final double LATERAL_BLOCKS = 40.0D;
    static final double APERTURE_PADDING = 0.75D;
    private static final Direction[] LOCAL_DIRS = {Direction.S, Direction.E, Direction.S, Direction.W, Direction.N, Direction.N, Direction.E, Direction.W};
    private static final double[][] LOCAL = {
        {634, 636.999, -4682, -4681.001, 635.4995, -4681.5005},
        {642, 642.999, -4686, -4683.001, 642.4995, -4684.5005},
        {638, 640.999, -4682, -4681.001, 639.4995, -4681.5005},
        {632, 632.999, -4686, -4683.001, 632.4995, -4684.5005},
        {638, 640.999, -4692, -4691.001, 639.4995, -4691.5005},
        {634, 636.999, -4692, -4691.001, 635.4995, -4691.5005},
        {642, 642.999, -4690, -4687.001, 642.4995, -4688.5005},
        {632, 632.999, -4690, -4687.001, 632.4995, -4688.5005}};
    private static final Direction[] REMOTE_DIRS = {Direction.N, Direction.E, Direction.S, Direction.W};

    private DomePlates() {
    }

    record Scenario(String name, AxisAlignedBB area, PortalFrame localFrame, PortalFrame remoteFrame, double lx, double ly, double lz,
                    double rx, double ry, double rz, boolean mirror) {
        ViewPlateBuilder.Request<String, String, ProjectionContentView<String, String>> request(SyntheticWorld world, boolean blockEntities) {
            PortalGeometry geometry = new PortalGeometry();
            geometry.setArea(area);
            ViewPlateKey key = new ViewPlateKey(UUID.nameUUIDFromBytes(name.getBytes()), world, false, 0, 0L);
            return new ViewPlateBuilder.Request<String, String, ProjectionContentView<String, String>>(key, geometry, world, localFrame,
                remoteFrame, lx, ly, lz, rx, ry, rz, mirror, 0, DEPTH_BLOCKS, LATERAL_BLOCKS, APERTURE_PADDING, true, SyntheticWorld.AIR,
                new LodPolicy(true, 32, 48), blockEntities, 0L, 0L, 0L, SyntheticBlocks.INSTANCE);
        }
    }

    static List<Scenario> scenarios(SyntheticWorld world, long seed) {
        List<Scenario> out = new ArrayList<Scenario>(9);
        AxisAlignedBB mirrorArea = new AxisAlignedBB(635, 639.999, 67, 67.999, -4689, -4684.001);
        PortalFrame up = PortalFrame.canonical(Direction.U);
        out.add(new Scenario("mirror-U", mirrorArea, up, up.flipNormal(), 637.4995, 67.4995, -4686.5005, 637.4995, 67.4995, -4686.5005, true));
        Random random = new Random(seed);
        int attempts = 0;
        while (out.size() < 9 && attempts++ < 4000) {
            int bx = random.nextInt(2400);
            int bz = random.nextInt(2400) - 1200;
            int surface = world.surface(bx, bz);
            if (surface == Integer.MIN_VALUE || surface <= 62) {
                continue;
            }
            int i = out.size() - 1;
            double[] l = LOCAL[i];
            AxisAlignedBB area = new AxisAlignedBB(l[0], l[1], 63, 65.999, l[2], l[3]);
            PortalFrame local = PortalFrame.canonical(LOCAL_DIRS[i]);
            PortalFrame remote = PortalFrame.canonical(REMOTE_DIRS[random.nextInt(4)]);
            out.add(new Scenario("rtp-" + i, area, local, remote, l[4], 64.4995, l[5], bx + 0.4995, surface + 1 + 1.4995, bz + 0.4995, false));
        }
        if (out.size() != 9) {
            throw new IllegalStateException("could not place nine dome plates in the synthetic world");
        }
        return out;
    }

    static List<ViewPlate<String>> build(SyntheticWorld world, long seed, boolean blockEntities) {
        List<ViewPlate<String>> plates = new ArrayList<ViewPlate<String>>(9);
        for (Scenario scenario : scenarios(world, seed)) {
            plates.add(ViewPlateBuilder.build(scenario.request(world, blockEntities)));
        }
        return plates;
    }
}
