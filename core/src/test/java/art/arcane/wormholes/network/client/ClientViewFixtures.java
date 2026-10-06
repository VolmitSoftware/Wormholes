package art.arcane.wormholes.network.client;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.math.Face;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.BrickCodec;
import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.stream.PlateSectionBox;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;

final class ClientViewFixtures {
    record Vector(String name, ClientViewMessage message, long caps, int seq, int flags) {
        boolean clientbound() {
            return message.type().isClientbound();
        }
    }

    private ClientViewFixtures() {
    }

    static List<Vector> vectors() {
        List<Vector> out = new ArrayList<Vector>();
        out.add(new Vector("offer", offer(), ViewStreamCapability.NONE, 0, ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("hello", hello(), ViewStreamCapability.NONE, 0, 0));
        out.add(new Vector("accept", accept(), ViewStreamCapability.NONE, 1, ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("decline", new ClientViewMessage.Decline(ClientViewMessage.DeclineReason.DATA_VERSION_MISMATCH), ViewStreamCapability.NONE, 1,
            ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("palette", palette(), ViewStreamCapability.ALL, 2, 0));
        out.add(new Vector("portal", portal(), ViewStreamCapability.ALL, 3, 0));
        out.add(new Vector("portal_nested", portalNested(), ViewStreamCapability.ALL, 3, 0));
        out.add(new Vector("portal_drop", new ClientViewMessage.PortalDrop(7), ViewStreamCapability.ALL, 4, ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("plate_begin_hashes", plateBegin(true), ViewStreamCapability.ALL, 5, 0));
        out.add(new Vector("plate_begin_plain", plateBegin(false), ViewStreamCapability.ALL & ~ViewStreamCapability.BRICK_CACHE.mask(), 5, 0));
        out.add(new Vector("plate_bricks", plateBricks(), ViewStreamCapability.ALL, 6, 0));
        out.add(new Vector("plate_end", new ClientViewMessage.PlateEnd(7, 3), ViewStreamCapability.ALL, 7, ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("plate_patch", platePatch(), ViewStreamCapability.ALL, 8, ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("plate_handle", new ClientViewMessage.PlateHandle(7, 4, 0x0123456789ABCDEFL), ViewStreamCapability.ALL, 9,
            ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("brick_miss", new ClientViewMessage.BrickMiss(List.of(
            new ClientViewMessage.BrickMiss.Plate(7, 3, new long[] {0x8000000000000005L, 0x3L}),
            new ClientViewMessage.BrickMiss.Plate(8, 1, new long[] {0x1L}))), ViewStreamCapability.ALL, 0, 0));
        out.add(new Vector("entity_swing", new ClientViewMessage.EntityEvent(7, 3, new UUID(12, 34), false, 3, 0),
            ViewStreamCapability.ALL, 10, ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("entity_hurt", new ClientViewMessage.EntityEvent(7, 4, new UUID(12, 34), true, 0, 179.5F),
            ViewStreamCapability.ALL, 10, ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("entity_frame", entityFrame(), ViewStreamCapability.ALL, 10, ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("entity_frame_delta", entityFrameDelta(), ViewStreamCapability.ALL, 10, ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("fx", fx(), ViewStreamCapability.ALL, 11, ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("atmosphere", new ClientViewMessage.Atmosphere(7, 18000L, 0.25F, 0.0F, ClientViewMessage.Atmosphere.FLAG_TIME
            | ClientViewMessage.Atmosphere.FLAG_WEATHER), ViewStreamCapability.ALL, 12, ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("session_reset", new ClientViewMessage.SessionReset(ClientViewMessage.ResetReason.TELEPORT), ViewStreamCapability.ALL, 13,
            ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("ack", new ClientViewMessage.Ack(13, 400, 12345), ViewStreamCapability.NONE, 0, 0));
        out.add(new Vector("view_stats", new ClientViewMessage.ViewStats(500, 3, 250000, 2, 640, 1900, 96), ViewStreamCapability.NONE, 0, 0));
        out.add(new Vector("plate_refused", new ClientViewMessage.PlateRefused(7, 3), ViewStreamCapability.NONE, 0, 0));
        out.add(new Vector("mesh_begin", new ClientViewMessage.MeshBegin(7, 12, new PlateBox(-512, -64, -512, 1024, 512, 512), 1024), ViewStreamCapability.ALL, 14, 0));
        out.add(new Vector("mesh_section", new ClientViewMessage.MeshSection(7, 12, -32, 4, -10, 1, 3, litBrick(0), new SectionBiomes(List.of("minecraft:plains"), new byte[0])), ViewStreamCapability.ALL, 15, 0));
        out.add(new Vector("mesh_section_biomes", new ClientViewMessage.MeshSection(7, 12, -32, 4, -10, 1, 3,
            Brick.empty(0), biomeHalo()), ViewStreamCapability.ALL, 15, 0));
        out.add(new Vector("mesh_drop", new ClientViewMessage.MeshDrop(7, 12, -32, 4, -10), ViewStreamCapability.ALL, 16, 0));
        out.add(new Vector("mesh_ack", new ClientViewMessage.MeshAck(7, 12, -32, 4, -10, 1), ViewStreamCapability.NONE, 0, 0));
        out.add(new Vector("mesh_local", new ClientViewMessage.MeshLocal(7, 12, 1, true,
            List.of(new ClientViewMessage.MeshCoordinate(-32, 4, -10)), List.of(new UUID(12, 34))), ViewStreamCapability.NONE, 0, 0));
        out.add(new Vector("mesh_cached", new ClientViewMessage.MeshCached(7, 12, 1, true,
            List.of(new ClientViewMessage.MeshClaim(-32, 4, -10, 0x1122334455667788L))), ViewStreamCapability.NONE, 0, 0));
        out.add(new Vector("mesh_reuse", new ClientViewMessage.MeshReuse(7, 12, -32, 4, -10, 2, 0x1122334455667788L), ViewStreamCapability.ALL, 16, 0));
        out.add(new Vector("environment", new ClientViewMessage.Environment(7, environment()), ViewStreamCapability.ALL, 17, 0));
        out.add(new Vector("travel_begin", travelBegin(), ViewStreamCapability.ALL, 18, 0));
        out.add(new Vector("travel_chunk", new ClientViewMessage.TravelChunk(new UUID(12, 34), 3L, -32, -10, 2, 0, 1,
            4, new byte[] {1, 2, 3, 4}), ViewStreamCapability.ALL, 19, 0));
        out.add(new Vector("travel_end", new ClientViewMessage.TravelEnd(new UUID(12, 34), 3L, 9L,
            List.of(new ClientViewMessage.TravelChunkRevision(-32, -10, 2))), ViewStreamCapability.ALL, 20,
            ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("travel_ready", new ClientViewMessage.TravelReady(new UUID(12, 34), 3L, 9L),
            ViewStreamCapability.NONE, 0, 0));
        out.add(new Vector("travel_commit", new ClientViewMessage.TravelCommit(new UUID(12, 34), 3L, 9L,
            "minecraft:the_nether", "minecraft:overworld", travelBegin().arrival(), new Vec3d(0.25D, -0.5D, 1.0D)), ViewStreamCapability.ALL, 21,
            ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("travel_cancel", new ClientViewMessage.TravelCancel(new UUID(12, 34), 3L),
            ViewStreamCapability.ALL, 22, ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("travel_cross", new ClientViewMessage.TravelCross(new UUID(12, 34), 3L, 9L,
            new ClientViewMessage.TravelPose(635.5D, 65.0D, -4681.4D, 90.0F, -12.0F),
            new Vec3d(635.5D, 66.62D, -4681.6D), new Vec3d(635.5D, 66.62D, -4681.4D)),
            ViewStreamCapability.NONE, 0, 0));
        byte[] travelHash = new byte[32];
        for (int index = 0; index < travelHash.length; index++) {
            travelHash[index] = (byte) index;
        }
        out.add(new Vector("travel_reuse", new ClientViewMessage.TravelReuse(new UUID(12, 34), 3L, -32, -10, 2, travelHash),
            ViewStreamCapability.ALL, 23, ViewStreamLimits.FLAG_LAST));
        out.add(new Vector("travel_cached", new ClientViewMessage.TravelCached(new UUID(12, 34), 3L, -32, -10, 2, travelHash, true),
            ViewStreamCapability.NONE, 0, 0));
        out.add(new Vector("entity_self", new ClientViewMessage.EntitySelf(new UUID(12, 34)),
            ViewStreamCapability.ALL, 24, ViewStreamLimits.FLAG_LAST));
        return out;
    }

    static ClientViewMessage.TravelBegin travelBegin() {
        ProjectionEnvironment base = environment();
        ProjectionEnvironment.World world = base.world();
        ProjectionEnvironment environment = new ProjectionEnvironment(base.gameTime(), base.sky(), base.fog(), base.lighting(),
            base.clouds(), ProjectionEnvironment.Transform.IDENTITY, base.dimension(),
            new ProjectionEnvironment.World("minecraft:overworld", world.clockTime(), world.biomeKey(), world.seaLevel(),
                world.blockLight(), world.skyLight(), world.logicalHeight(), world.hasCeiling(), world.ambientLight(),
                world.eyeMedium(), world.hasFixedTime()));
        return new ClientViewMessage.TravelBegin(new UUID(12, 34), 3L, new UUID(56, 78), "minecraft:the_nether",
            geometry(List.of()), new ProjectionEnvironment.Transform(Face.S, Face.U, Face.E, new Vec3d(4, 0, 6)),
            new ClientViewMessage.TravelWorld("minecraft:overworld", "minecraft:overworld", 123456789L, false, true, 63, -64, 384),
            new ClientViewMessage.TravelPose(-511.5D, 81.0D, -159.5D, 90.0F, -12.0F),
            List.of(new ClientViewMessage.TravelCoordinate(-32, -10)), environment, 30_000);
    }

    static SectionBiomes biomeHalo() {
        List<String> palette = new ArrayList<>(SectionBiomes.CELLS);
        byte[] indices = new byte[SectionBiomes.INDEX_BYTES];
        for (int cell = 0; cell < SectionBiomes.CELLS; cell++) {
            palette.add("test:biome_" + cell);
            indices[cell * 2] = (byte) cell;
            indices[cell * 2 + 1] = (byte) (cell >>> 8);
        }
        return new SectionBiomes(palette, indices);
    }

    static ProjectionEnvironment environment() {
        ProjectionEnvironment.Color color = new ProjectionEnvironment.Color(0.125F, 0.5F, 1.25F);
        ProjectionEnvironment.ColorAlpha alpha = new ProjectionEnvironment.ColorAlpha(0.75F, 0.5F, 0.25F, 0.5F);
        return new ProjectionEnvironment(18000L,
            new ProjectionEnvironment.Sky(ProjectionEnvironment.Skybox.OVERWORLD, 1.5F, 2.5F, 3.5F, 0.8F, alpha, color, 5, 0.25F, 0.5F),
            new ProjectionEnvironment.Fog(color, -8.0F, 96.0F, 512.0F, 256.0F, color, 0.0F, 32.0F),
            new ProjectionEnvironment.Lighting(color, 0.75F, color, color), new ProjectionEnvironment.Clouds(alpha, 192.0F),
            new ProjectionEnvironment.Transform(Face.N, Face.U, Face.E, new Vec3d(-128.5D, 96.0D, 33.25D)),
            new ProjectionEnvironment.Dimension(-64, 384, true, ProjectionEnvironment.CardinalLighting.DEFAULT, 63.0D, false),
            new ProjectionEnvironment.World("test:destination", 72000L, "minecraft:plains", 63, 7, 15, 256, true, 0.1F, ProjectionEnvironment.EyeMedium.WATER, true));
    }

    static ClientViewMessage.Offer offer() {
        return new ClientViewMessage.Offer(ViewStreamLimits.WIRE_VERSION, 4325, ViewStreamCapability.ALL, ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES,
            0x1122334455667788L);
    }

    static ClientViewMessage.Hello hello() {
        return new ClientViewMessage.Hello(ViewStreamLimits.WIRE_VERSION, 4325,
            ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.BRICK_CACHE, ViewStreamCapability.DEST_LIGHT,
                ViewStreamCapability.ENTITY_FRAMES, ViewStreamCapability.VIEW_STATS),
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 256, 0x1122334455667788L, "fabric");
    }

    static ClientViewMessage.Accept accept() {
        return new ClientViewMessage.Accept(42, ViewStreamCapability.of(ViewStreamCapability.PLATES, ViewStreamCapability.BRICK_CACHE,
            ViewStreamCapability.DEST_LIGHT, ViewStreamCapability.ENTITY_FRAMES, ViewStreamCapability.VIEW_STATS), 20,
            ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 0x0F1E2D3C4B5A6978L, 8);
    }

    static ClientViewMessage.Palette palette() {
        return new ClientViewMessage.Palette(List.of(
            new ClientViewMessage.PaletteEntry(3, "minecraft:stone"),
            new ClientViewMessage.PaletteEntry(4, "minecraft:grass_block[snowy=false]"),
            new ClientViewMessage.PaletteEntry(5, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")));
    }

    static ApertureDescriptor geometry(List<ApertureDescriptor> nested) {
        boolean[] open = new boolean[3 * 3];
        for (int i = 0; i < open.length; i++) {
            open[i] = i != 4;
        }
        return new ApertureDescriptor(635, 64, -4682, 3, true, 0, false, 3, 3, ApertureDescriptor.apertureMask(3, 3, open),
            0.25F, 0.75F, 1.2F, 64, 1, 1, 6, 0, 1, ApertureDescriptor.FIDELITY_DISPLAY_ENTITIES | ApertureDescriptor.FIDELITY_WEATHER,
            ApertureDescriptor.KIND_RTP, 0.0D, 0, 0x7A7A7A7A7A7A7A7AL, nested);
    }

    static ClientViewMessage.Portal portal() {
        return new ClientViewMessage.Portal(7, 2, geometry(List.of()));
    }

    static ClientViewMessage.Portal portalNested() {
        ApertureDescriptor child = new ApertureDescriptor(2, 0, 5, 2, false, 1, true, 2, 2, ApertureDescriptor.apertureMask(2, 2,
            new boolean[] {true, true, true, true}), 0.25F, 0.5F, 1.0F, 32, 0, 0, 0, 1, 0, 0, ApertureDescriptor.KIND_FRAME, 0.0D, 7, 0L, List.of());
        return new ClientViewMessage.Portal(8, 1, geometry(List.of(child)));
    }

    static ClientViewMessage.PlateBegin plateBegin(boolean hashes) {
        PlateSectionBox sections = new PlateSectionBox(37, 1, -294, 2, 1, 2);
        PlateBox cells = new PlateBox(595, 22, -4700, 25, 16, 20);
        long[] manifest = hashes ? new long[] {1L, 2L, 3L, 0xFFFFFFFFFFFFFFFFL} : null;
        return new ClientViewMessage.PlateBegin(7, 3, sections, cells, 3, 4, manifest);
    }

    static Brick palettedBrick(int index) {
        int[] cells = new int[ViewStreamLimits.BRICK_CELLS];
        for (int i = 0; i < cells.length; i++) {
            int y = ViewStreamLimits.brickCellY(i);
            cells[i] = y < 6 ? ViewStreamLimits.PALETTE_OCCLUDED : y < 8 ? ViewStreamLimits.PALETTE_BACKING : y == 8 ? 4 : (i % 97 == 0 ? 5 : 0);
        }
        return BrickCodec.pack(index, cells);
    }

    static Brick litBrick(int index) {
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        for (int i = 0; i < ViewStreamLimits.BRICK_CELLS; i++) {
            BrickLightSource.setNibble(block, i, (i * 7) & 15);
            BrickLightSource.setNibble(sky, i, ViewStreamLimits.brickCellY(i) >= 8 ? 15 : 0);
        }
        Brick.BlockEntityCell[] blockEntities = {new Brick.BlockEntityCell(ViewStreamLimits.brickCellIndex(3, 8, 3), new byte[] {10, 0, 0, 0})};
        return palettedBrick(index).withLight(block, sky).withBlockEntities(blockEntities);
    }

    static Brick uniformlyLitBrick(int index) {
        byte[] block = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ViewStreamLimits.LIGHT_NIBBLE_BYTES];
        Arrays.fill(sky, (byte) 0xFF);
        return Brick.single(index, 3).withLight(block, sky);
    }

    static ClientViewMessage.PlateBricks plateBricks() {
        return new ClientViewMessage.PlateBricks(7, 3, List.of(Brick.empty(0), uniformlyLitBrick(1), palettedBrick(2), litBrick(3)));
    }

    static ClientViewMessage.PlatePatch platePatch() {
        return new ClientViewMessage.PlatePatch(7, 3, 4, List.of(
            new ClientViewMessage.ClearOp(0),
            new ClientViewMessage.SparseOp(1, new int[] {0, 17, 4095}, new int[] {4, 0, 5}),
            new ClientViewMessage.FullOp(palettedBrick(2))));
    }

    static ClientViewMessage.EntityFrame entityFrame() {
        UUID first = new UUID(0x1000000000000001L, 0x2000000000000002L);
        UUID second = new UUID(0x3000000000000003L, 0x4000000000000004L);
        EntitySnapshot full = EntitySnapshot.full(first, "minecraft:armor_stand", 1.5D, 64.0D, -3.25D, 1.5D, 0.0D, 0.0D, 1.0D, 90.0F, 0.0F,
            0.0D, -0.0625D, 0.0D, true, null, null, null, null, null, new byte[] {1, 2, 3}, new byte[] {4}, EntitySnapshot.EMPTY, 9);
        EntitySnapshot delta = EntityDeltaSupport.delta(second, 10);
        return new ClientViewMessage.EntityFrame(7, 77, List.of(full, delta), List.of(first, second), true);
    }

    static ClientViewMessage.EntityFrame entityFrameDelta() {
        ClientViewMessage.EntityFrame base = entityFrame();
        return new ClientViewMessage.EntityFrame(7, 78, List.of(base.entities().get(1)), List.of(), false);
    }

    static ClientViewMessage.Fx fx() {
        return new ClientViewMessage.Fx(7, List.of(
            new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.RIM_DUST, "minecraft:portal", 635.5D, 64.5D, -4681.5D, 1.0F, 0.0F, 5, 0),
            new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.SOUND, "minecraft:block.portal.ambient", 636.0D, 65.0D, -4681.0D, 0.5F, 1.1F, 0, 1),
            new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.ANIMATION, "", 637.5D, 66.0D, -4686.5D, 3.0F, 4.0F, 0, 0x42),
            new ClientViewMessage.FxEmitter(ClientViewMessage.FxKind.BURST, "minecraft:reverse_portal", 637.5D, 66.0D, -4686.5D, 0.4F, 0.6F, 12, 40)));
    }

    static final class EntityDeltaSupport {
        private EntityDeltaSupport() {
        }

        static EntitySnapshot delta(UUID id, int sequence) {
            return new EntitySnapshot(EntitySnapshot.MODE_DELTA, sequence, EntitySnapshot.FIELD_POSITION | EntitySnapshot.FIELD_YAW_PITCH, id, "",
                2.0D, 65.0D, -2.0D, 0.0D, 0.0D, 0.0D, 0.0D, 45.0F, 45.0F, 0.0D, 0.0D, 0.0D, false, "", "", "", null, null,
                EntitySnapshot.EMPTY, EntitySnapshot.EMPTY, EntitySnapshot.EMPTY);
        }
    }
}
