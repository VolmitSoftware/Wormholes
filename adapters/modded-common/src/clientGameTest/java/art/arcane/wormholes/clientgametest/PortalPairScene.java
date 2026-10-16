package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalItems;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.portal.PortalStateCodec;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static art.arcane.wormholes.clientgametest.NativeClientViewAssertions.runtime;
import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

final class PortalPairScene {
    static final BlockPos LOCAL_MIN = new BlockPos(40, 70, 40);
    static final BlockPos END_MIN = new BlockPos(40, 70, 400);
    static final BlockPos SAME_DIMENSION_MIN = new BlockPos(40, 70, 160);
    private static final double VERTICAL_APPROACH = 4.5D;
    private static final double DROP_HEIGHT = 3.0D;
    private static final int SHAFT_DEPTH = 6;
    private static final float BUILD_PITCH = 60.0F;
    private static final TypeToken<Map<String, Object>> DOCUMENT = new TypeToken<>() { };

    private PortalPairScene() {
    }

    static Pair build(ServerPlayer player, MinecraftServer server, Spec spec) {
        WormholesModRuntime runtime = runtime(server);
        player.setGameMode(GameType.CREATIVE);
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        prepare(level(server, Level.OVERWORLD), LOCAL_MIN, spec.orientation());
        prepare(level(server, spec.remote()), remoteMin(spec.remote()), spec.orientation());
        player.setItemInHand(InteractionHand.MAIN_HAND, MinecraftPortalItems.of(runtime).wand());
        boolean startLocal = spec.start() == Side.LOCAL;
        UUID first = startLocal ? construct(runtime, player, level(server, spec.remote()), remoteMin(spec.remote()), spec.orientation())
            : construct(runtime, player, level(server, Level.OVERWORLD), LOCAL_MIN, spec.orientation());
        UUID second = startLocal ? construct(runtime, player, level(server, Level.OVERWORLD), LOCAL_MIN, spec.orientation())
            : construct(runtime, player, level(server, spec.remote()), remoteMin(spec.remote()), spec.orientation());
        MinecraftPortal source = Objects.requireNonNull(runtime.portals().get(second), "second portal");
        MinecraftPortal destination = Objects.requireNonNull(runtime.portals().get(first), "first portal");
        assertTrue(runtime.portals().link(player, source.getId(), destination.getId()), "destination link rejected");
        if (spec.link() == Link.MUTUAL) {
            assertTrue(runtime.portals().link(player, destination.getId(), source.getId()), "return link rejected");
        } else if (spec.link() == Link.LINK_AND_RETURN) {
            runtime.nexus().pair(player, source, destination, true);
            assertTrue(second.equals(destination.getDestinationId()), "link and return did not create a reciprocal pair");
        }
        UUID local = startLocal ? second : first;
        UUID remote = startLocal ? first : second;
        Pair pair = new Pair(local, remote, spec.remote(), spec.orientation());
        stance(player, server, pair, spec.start(), Stance.ENTRY);
        return pair;
    }

    static void stance(ServerPlayer player, MinecraftServer server, Pair pair, Side side, Stance stance) {
        ServerLevel level = level(server, pair.level(side));
        BlockPos min = pair.min(side);
        boolean floor = pair.orientation() == Orientation.FLOOR;
        Vec3 position = switch (stance) {
            case ENTRY -> floor ? new Vec3(min.getX() + 1.5D, min.getY() + DROP_HEIGHT, min.getZ() + 1.5D)
                : new Vec3(min.getX() + 1.5D, min.getY(), min.getZ() + VERTICAL_APPROACH);
            case BEHIND -> new Vec3(min.getX() + 1.5D, min.getY(), min.getZ() - VERTICAL_APPROACH + 1.0D);
        };
        float yaw = stance == Stance.BEHIND ? 0.0F : 180.0F;
        float pitch = floor ? 90.0F : 0.0F;
        player.getAbilities().flying = floor && stance == Stance.ENTRY;
        player.onUpdateAbilities();
        player.teleportTo(level, position.x, position.y, position.z, Set.of(), yaw, pitch, false);
        player.setDeltaMovement(Vec3.ZERO);
    }

    static void fall(ServerPlayer player) {
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
    }

    static String dimension(ServerPlayer player) {
        return player.level().dimension().identifier().toString();
    }

    static boolean exists(MinecraftServer server, UUID portal) {
        return runtime(server).portals().get(portal) != null;
    }

    static String name(MinecraftServer server, UUID portal) {
        return Objects.requireNonNull(runtime(server).portals().get(portal), "portal " + portal).getName();
    }

    static Stored stored(MinecraftServer server, UUID portal) {
        WormholesModRuntime runtime = runtime(server);
        runtime.portals().flushWrites().join();
        Path file = PortalStateCodec.file(runtime.stores().data().resolve("portals"), portal);
        MinecraftPortal live = runtime.portals().get(portal);
        Map<String, String> liveTunnel = live == null || live.getDestinationId() == null ? Map.of()
            : Map.of("type", live.getTunnelType(), "destination", live.getDestinationId().toString());
        boolean liveReciprocal = live != null && Boolean.TRUE.equals(live.setting("nexus.reciprocal"));
        if (!Files.isRegularFile(file)) {
            return new Stored(live != null, liveTunnel, liveReciprocal, false, Map.of(), false);
        }
        try {
            Map<String, Object> document = new Gson().fromJson(Files.readString(file), DOCUMENT);
            Map<String, String> tunnel = new LinkedHashMap<>();
            if (document.get("tunnel") instanceof Map<?, ?> saved) {
                for (Map.Entry<?, ?> entry : saved.entrySet()) {
                    tunnel.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
            return new Stored(live != null, liveTunnel, liveReciprocal, true, Map.copyOf(tunnel), Boolean.TRUE.equals(document.get("nexus.reciprocal")));
        } catch (IOException failure) {
            throw new AssertionError("could not read the saved portal " + file, failure);
        }
    }

    static int gunpowder(Inventory inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.GUNPOWDER)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    static void remove(ServerPlayer player, MinecraftServer server, Pair pair) {
        WormholesModRuntime runtime = runtime(server);
        runtime.portals().remove(player, pair.local());
        runtime.portals().remove(player, pair.remote());
    }

    static BlockPos remoteMin(ResourceKey<Level> remote) {
        return remote == Level.OVERWORLD ? SAME_DIMENSION_MIN : END_MIN;
    }

    private static UUID construct(WormholesModRuntime runtime, ServerPlayer player, ServerLevel level, BlockPos min, Orientation orientation) {
        boolean floor = orientation == Orientation.FLOOR;
        BlockPos far = floor ? min.offset(2, 0, 2) : min.offset(2, 2, 0);
        level.setBlockAndUpdate(min, Blocks.GLASS.defaultBlockState());
        level.setBlockAndUpdate(far, Blocks.GLASS.defaultBlockState());
        Vec3 stand = floor ? new Vec3(min.getX() - 1.5D, min.getY() + 1.0D, min.getZ() + 1.5D)
            : new Vec3(min.getX() + 1.5D, min.getY(), min.getZ() + VERTICAL_APPROACH);
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        player.teleportTo(level, stand.x, stand.y, stand.z, Set.of(), floor ? -90.0F : 180.0F, floor ? BUILD_PITCH : 0.0F, false);
        assertTrue(runtime.attackBlock(player, min), "the wand did not take the first corner at " + min);
        assertTrue(runtime.useBlock(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(far),
            floor ? Direction.UP : Direction.SOUTH, far, false)), "the wand did not take the second corner at " + far);
        assertTrue(runtime.attackBlock(player, min.offset(1, floor ? 0 : 1, floor ? 1 : 0)), "the wand did not open the selection at " + min);
        MinecraftPortal portal = runtime.portals().at(level, min);
        assertTrue(portal != null, "the wand selection at " + min + " in " + level.dimension().identifier() + " did not open a portal");
        level.setBlockAndUpdate(min, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(far, Blocks.AIR.defaultBlockState());
        return portal.getId();
    }

    private static void prepare(ServerLevel level, BlockPos min, Orientation orientation) {
        if (orientation == Orientation.FLOOR) {
            fill(level, min.offset(-5, -SHAFT_DEPTH - 1, -5), 13, SHAFT_DEPTH + 2, 13, Blocks.STONE.defaultBlockState());
            fill(level, min.offset(-5, 1, -5), 13, 7, 13, Blocks.AIR.defaultBlockState());
            fill(level, min.offset(0, -SHAFT_DEPTH, 0), 3, SHAFT_DEPTH + 1, 3, Blocks.AIR.defaultBlockState());
            fill(level, min.offset(0, -SHAFT_DEPTH - 1, 0), 3, 1, 3, Blocks.SEA_LANTERN.defaultBlockState());
            return;
        }
        fill(level, min.offset(-6, -1, -8), 15, 1, 17, Blocks.STONE.defaultBlockState());
        fill(level, min.offset(-6, 0, -8), 15, 7, 17, Blocks.AIR.defaultBlockState());
        for (int x = -1; x <= 3; x++) {
            level.setBlockAndUpdate(min.offset(x, -1, 0), Blocks.OBSIDIAN.defaultBlockState());
            level.setBlockAndUpdate(min.offset(x, 3, 0), Blocks.OBSIDIAN.defaultBlockState());
        }
        for (int y = 0; y < 3; y++) {
            level.setBlockAndUpdate(min.offset(-1, y, 0), Blocks.OBSIDIAN.defaultBlockState());
            level.setBlockAndUpdate(min.offset(3, y, 0), Blocks.OBSIDIAN.defaultBlockState());
        }
        level.setBlockAndUpdate(min.offset(1, 4, -3), Blocks.SEA_LANTERN.defaultBlockState());
        level.setBlockAndUpdate(min.offset(1, 4, 3), Blocks.SEA_LANTERN.defaultBlockState());
    }

    private static ServerLevel level(MinecraftServer server, ResourceKey<Level> key) {
        return Objects.requireNonNull(server.getLevel(key), "level " + key);
    }

    private static void fill(ServerLevel level, BlockPos origin, int width, int height, int depth, BlockState state) {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    level.setBlockAndUpdate(origin.offset(x, y, z), state);
                }
            }
        }
    }

    enum Orientation {
        VERTICAL,
        FLOOR
    }

    enum Side {
        LOCAL,
        REMOTE
    }

    enum Stance {
        ENTRY,
        BEHIND
    }

    enum Link {
        ONE_WAY,
        MUTUAL,
        LINK_AND_RETURN
    }

    record Spec(Orientation orientation, ResourceKey<Level> remote, Side start, Link link) {
    }

    record Stored(boolean registered, Map<String, String> liveTunnel, boolean liveReciprocal, boolean saved, Map<String, String> savedTunnel,
                  boolean savedReciprocal) {
        boolean absent() {
            return !registered && !saved;
        }

        boolean keeps(Map<String, String> tunnel) {
            return registered && saved && liveTunnel.equals(tunnel) && savedTunnel.equals(tunnel) && !liveReciprocal && !savedReciprocal;
        }
    }

    record Pair(UUID local, UUID remote, ResourceKey<Level> remoteLevel, Orientation orientation) {
        ResourceKey<Level> level(Side side) {
            return side == Side.LOCAL ? Level.OVERWORLD : remoteLevel;
        }

        BlockPos min(Side side) {
            return side == Side.LOCAL ? LOCAL_MIN : remoteMin(remoteLevel);
        }

        UUID portal(Side side) {
            return side == Side.LOCAL ? local : remote;
        }

        Vec3 center(Side side) {
            BlockPos min = min(side);
            return orientation == Orientation.FLOOR ? new Vec3(min.getX() + 1.5D, min.getY() + 0.5D, min.getZ() + 1.5D)
                : new Vec3(min.getX() + 1.5D, min.getY() + 1.5D, min.getZ() + 0.5D);
        }
    }
}
