package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.Brick;
import art.arcane.wormholes.network.client.BrickCodec;
import art.arcane.wormholes.network.client.ClientViewCapability;
import art.arcane.wormholes.network.client.ClientViewCodec;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.client.ClientViewProtocolException;
import art.arcane.wormholes.network.client.PlateSectionBox;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.portal.effects.PortalAnimation;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.util.Direction;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

final class ClientViewHarness {
    static final BlockState STONE = Blocks.STONE.defaultBlockState();
    static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    static final BlockState AIR = Blocks.AIR.defaultBlockState();
    static final int STONE_ID = 3;
    static final int PORTAL_KEY = 1;
    static final PlateBox PLATE = new PlateBox(-8, 56, 0, 18, 18, 10);
    static final PlateSectionBox SECTIONS = PlateSectionBox.snap(PLATE);
    static final int AIR_COLUMN_X = 1;
    static final int REAL_AIR_Z = 3;
    static final double EYE_X = 1.5D;
    static final double EYE_Y = 65.5D;
    static final double EYE_Z = 15.5D;
    static final int LOCAL_BLOCK_LIGHT = 3;
    static final int LOCAL_SKY_LIGHT = 7;
    static final int DESTINATION_BLOCK_LIGHT = 12;
    static final int DESTINATION_SKY_LIGHT = 9;

    final WormholesClientConfig config;
    final ClientViewSession session;
    final ClientViewReceiver receiver;
    final ClientViewTick tick;
    final ClientViewStats stats;
    final FakeSurface surface;
    final FakeScene scene;
    final List<ClientViewMessage> sent;
    int seq;
    int lastSeq;

    ClientViewHarness() {
        config = new WormholesClientConfig();
        config.normalize();
        session = new ClientViewSession(config, new ClientPalette(BuiltInRegistries.BLOCK), 1, "test");
        session.accept(new ClientViewMessage.Accept(1, ClientViewCapability.ALL, 20, ClientViewProtocol.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
        receiver = new ClientViewReceiver(session);
        stats = new ClientViewStats();
        tick = new ClientViewTick(session, receiver, config, stats);
        sent = new ArrayList<>();
        tick.sender(sent::add);
        surface = new FakeSurface();
        scene = new FakeScene();
        tick.attach(new Object(), surface, scene);
    }

    void stream() throws ClientViewProtocolException {
        receive(new ClientViewMessage.Palette(List.of(new ClientViewMessage.PaletteEntry(STONE_ID, "minecraft:stone"))), 0);
        receive(new ClientViewMessage.Portal(PORTAL_KEY, 1, geometry()), 0);
        Brick[] bricks = new Brick[SECTIONS.brickCount()];
        for (int index = 0; index < bricks.length; index++) {
            bricks[index] = plateBrick(index);
        }
        long[] hashes = new long[bricks.length];
        for (int index = 0; index < hashes.length; index++) {
            hashes[index] = 0x5000L + index;
        }
        receive(new ClientViewMessage.PlateBegin(PORTAL_KEY, 1, SECTIONS, PLATE, STONE_ID, bricks.length, hashes), 0);
        receive(new ClientViewMessage.PlateBricks(PORTAL_KEY, 1, Arrays.asList(bricks)), 0);
        receive(new ClientViewMessage.PlateEnd(PORTAL_KEY, 1), ClientViewProtocol.FLAG_LAST);
    }

    void receive(ClientViewMessage message, int flags) throws ClientViewProtocolException {
        lastSeq = ++seq;
        receiver.receive(ClientViewCodec.encodeS2C(message, lastSeq, flags), null);
        assertEquals("decode failed for " + message.type(), 0L, receiver.decodeFailures());
    }

    void tick(double eyeX, double eyeY, double eyeZ) {
        tick.tick(eyeX, eyeY, eyeZ, 0.0D, 0.0D, 0.0D, System.currentTimeMillis());
    }

    List<ClientViewMessage.Ack> acks() {
        List<ClientViewMessage.Ack> acks = new ArrayList<>();
        for (ClientViewMessage message : sent) {
            if (message instanceof ClientViewMessage.Ack ack) {
                acks.add(ack);
            }
        }
        return acks;
    }

    boolean plateContains(int x, int y, int z) {
        return x >= PLATE.minX() && x < PLATE.minX() + PLATE.sizeX() && y >= PLATE.minY() && y < PLATE.minY() + PLATE.sizeY()
            && z >= PLATE.minZ() && z < PLATE.minZ() + PLATE.sizeZ();
    }

    static Brick brick(int brickIndex, int fill) {
        int[] cells = new int[ClientViewProtocol.BRICK_CELLS];
        Arrays.fill(cells, fill);
        return BrickCodec.pack(brickIndex, cells);
    }

    static Brick plateBrick(int brickIndex) {
        int baseX = SECTIONS.sectionX(brickIndex) << 4;
        int baseY = SECTIONS.sectionY(brickIndex) << 4;
        int baseZ = SECTIONS.sectionZ(brickIndex) << 4;
        int[] cells = new int[ClientViewProtocol.BRICK_CELLS];
        for (int cellIndex = 0; cellIndex < cells.length; cellIndex++) {
            int x = baseX + ClientViewProtocol.brickCellX(cellIndex);
            int y = baseY + ClientViewProtocol.brickCellY(cellIndex);
            int z = baseZ + ClientViewProtocol.brickCellZ(cellIndex);
            boolean inside = x >= PLATE.minX() && x < PLATE.minX() + PLATE.sizeX() && y >= PLATE.minY() && y < PLATE.minY() + PLATE.sizeY()
                && z >= PLATE.minZ() && z < PLATE.minZ() + PLATE.sizeZ();
            cells[cellIndex] = !inside || x == AIR_COLUMN_X ? ClientViewProtocol.PALETTE_AIR : STONE_ID;
        }
        Brick brick = BrickCodec.pack(brickIndex, cells);
        byte[] block = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        byte[] sky = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
        Arrays.fill(block, (byte) ((DESTINATION_BLOCK_LIGHT << 4) | DESTINATION_BLOCK_LIGHT));
        Arrays.fill(sky, (byte) ((DESTINATION_SKY_LIGHT << 4) | DESTINATION_SKY_LIGHT));
        return brick.isEmpty() ? brick : brick.withLight(block, sky);
    }

    static ClientPortalGeometry geometry() {
        boolean[] open = new boolean[9];
        Arrays.fill(open, true);
        return new ClientPortalGeometry(0, 64, 10, Direction.S.ordinal(), true, 0, false, 3, 3,
            ClientPortalGeometry.apertureMask(3, 3, open), 0.0F, 0.0F, 0.0F, 8, 0,
            ClientPortalGeometry.BLACKOUT_OFF, 0, ClientPortalGeometry.MASK_AIR_PROJECT, 0, 0,
            ClientPortalGeometry.KIND_FRAME, 0, 0L, List.of());
    }

    static final class FakeSurface implements ClientViewSurface {
        final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();
        final LongOpenHashSet unloaded = new LongOpenHashSet();
        final LongArrayList writes = new LongArrayList();
        final Map<Long, byte[][]> light = new HashMap<>();
        ClientLightPatches patches;
        int lightSections;
        int flushes;

        static BlockState real(int x, int y, int z) {
            return x == AIR_COLUMN_X && z == REAL_AIR_Z ? AIR : DIRT;
        }

        @Override
        public boolean chunkLoaded(int chunkX, int chunkZ) {
            return !unloaded.contains(ChunkPos.pack(chunkX, chunkZ));
        }

        @Override
        public BlockState state(int x, int y, int z) {
            if (!chunkLoaded(x >> 4, z >> 4)) {
                return null;
            }
            BlockState state = states.get(ProjectionCellKey.pack(x, y, z));
            return state == null ? real(x, y, z) : state;
        }

        @Override
        public void write(int x, int y, int z, BlockState state) {
            assertNotNull(state);
            assertTrue("write into a missing chunk", chunkLoaded(x >> 4, z >> 4));
            long key = ProjectionCellKey.pack(x, y, z);
            if (state == real(x, y, z)) {
                states.remove(key);
            } else {
                states.put(key, state);
            }
            writes.add(key);
        }

        @Override
        public void blockEntity(int x, int y, int z, BlockEntitySample sample) {
        }

        @Override
        public void attachLight(ClientLightPatches attached) {
            patches = attached;
        }

        @Override
        public void detachLight(ClientLightPatches attached) {
            if (patches == attached) {
                patches = null;
            }
        }

        @Override
        public void lightChanged(int sectionX, int sectionY, int sectionZ) {
            lightSections++;
        }

        @Override
        public int skyDarken() {
            return 0;
        }

        @Override
        public void flush() {
            flushes++;
        }

        int changedCells() {
            return states.size();
        }

        void replaceLight(int sectionX, int sectionY, int sectionZ, int block, int sky) {
            byte[] blockLayer = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
            byte[] skyLayer = new byte[ClientViewProtocol.LIGHT_NIBBLE_BYTES];
            Arrays.fill(blockLayer, (byte) ((block << 4) | block));
            Arrays.fill(skyLayer, (byte) ((sky << 4) | sky));
            light.put(SectionPos.asLong(sectionX, sectionY, sectionZ), new byte[][] {blockLayer, skyLayer});
        }

        int blockLight(int x, int y, int z) {
            int patched = patches == null ? ClientLightPatches.NO_LIGHT : patches.value(false, x, y, z, 0);
            if (patched >= 0) {
                return patched;
            }
            byte[][] layers = light.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
            return layers == null ? LOCAL_BLOCK_LIGHT : BrickLightSource.nibble(layers[0], ClientViewProtocol.brickCellIndex(x, y, z));
        }

        int skyLight(int x, int y, int z) {
            int patched = patches == null ? ClientLightPatches.NO_LIGHT : patches.value(true, x, y, z, 0);
            if (patched >= 0) {
                return patched;
            }
            byte[][] layers = light.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
            return layers == null ? LOCAL_SKY_LIGHT : BrickLightSource.nibble(layers[1], ClientViewProtocol.brickCellIndex(x, y, z));
        }

        ProjectionOverlay.ChunkSections sections() {
            return new ProjectionOverlay.ChunkSections() {
                @Override
                public BlockState state(int x, int y, int z) {
                    BlockState state = states.get(ProjectionCellKey.pack(x, y, z));
                    return state == null ? real(x, y, z) : state;
                }

                @Override
                public void write(int x, int y, int z, BlockState state) {
                    FakeSurface.this.write(x, y, z, state);
                }

                @Override
                public void blockEntity(int x, int y, int z, BlockEntitySample sample) {
                    FakeSurface.this.blockEntity(x, y, z, sample);
                }
            };
        }

    }

    static final class FakeScene implements ClientSceneWorld {
        final Map<Integer, EntityVisual> entities = new HashMap<>();
        final Map<Integer, byte[]> metadata = new HashMap<>();
        final List<String> events = new ArrayList<>();
        final List<String> particles = new ArrayList<>();
        int moves;
        float rain;
        float thunder;
        boolean clockPresent = true;
        long clock = 1000L;
        long gameTime = 50L;
        boolean failGameTime;

        @Override
        public boolean spawn(int entityId, EntityVisual visual) {
            entities.put(entityId, visual);
            events.add("spawn " + entityId);
            return true;
        }

        @Override
        public void move(int entityId, EntityVisual visual) {
            entities.put(entityId, visual);
            moves++;
        }

        @Override
        public void metadata(int entityId, byte[] bytes) {
            metadata.put(entityId, bytes);
        }

        @Override
        public void equipment(int entityId, byte[] equipment) {
        }

        @Override
        public void remove(int entityId, EntityVisual visual) {
            entities.remove(entityId);
            events.add("remove " + entityId);
        }

        @Override
        public void particle(String key, double x, double y, double z, double spread, double speed, int count) {
            particles.add(key + " x" + count);
        }

        @Override
        public void burst(String key, double x, double y, double z, double spreadHorizontal, double spreadVertical, double speed, int count) {
            particles.add("burst " + key + " x" + count);
        }

        @Override
        public void emission(PortalAnimation.ParticleEmission emission) {
            particles.add("animation " + emission.type());
        }

        @Override
        public void dust(double x, double y, double z, int rgb, float scale) {
            particles.add("dust " + Integer.toHexString(rgb));
        }

        @Override
        public void sound(String key, double x, double y, double z, float volume, float pitch, AcousticsProfile.SoundClass soundClass) {
            events.add("sound " + key + " " + soundClass);
        }

        @Override
        public float rain() {
            return rain;
        }

        @Override
        public float thunder() {
            return thunder;
        }

        @Override
        public void weather(float nextRain, float nextThunder) {
            rain = nextRain;
            thunder = nextThunder;
        }

        @Override
        public boolean hasClock() {
            return clockPresent;
        }

        @Override
        public long clock() {
            return clock;
        }

        @Override
        public void clock(long ticks) {
            clock = ticks;
        }

        @Override
        public long gameTime() {
            if (failGameTime) {
                throw new IllegalStateException("scene clock unavailable");
            }
            return gameTime;
        }
    }
}
