package art.arcane.wormholes.modded;

import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.PortalConstruction;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class MinecraftPortalConstruction implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final Map<MinecraftServer, MinecraftPortalConstruction> SERVICES = new HashMap<>();
    private final WormholesModRuntime runtime;
    private MinecraftServer server;
    private final MinecraftVanillaPortals vanilla;
    private final Map<ServerLevel, Map<PortalConstruction.Cell, Rune>> runes = new HashMap<>();
    private long tick;

    public MinecraftPortalConstruction(WormholesModRuntime runtime) {
        this.runtime = runtime;
        vanilla = new MinecraftVanillaPortals(runtime);
    }

    public void load() {
        runtime.requireServerThread();
        runes.clear();
        server = runtime.server();
        SERVICES.put(server, this);
        tick = 0;
    }

    public static MinecraftPortalConstruction forServer(MinecraftServer server) {
        return SERVICES.get(server);
    }

    public MinecraftVanillaPortals vanilla() {
        return vanilla;
    }

    public void chunkUnloaded(ServerLevel level, int chunkX, int chunkZ) {
        Map<PortalConstruction.Cell, Rune> placed = runes.get(level);
        if (placed == null) {
            return;
        }
        for (Map.Entry<PortalConstruction.Cell, Rune> entry : placed.entrySet()) {
            if (entry.getKey().x() >> 4 == chunkX && entry.getKey().z() >> 4 == chunkZ) {
                entry.getValue().unloaded = true;
            }
        }
    }

    public boolean useBlock(ServerPlayer player, InteractionHand hand, BlockHitResult hit) {
        runtime.requireServerThread();
        ItemStack held = player.getItemInHand(hand);
        Optional<PortalType> type = runeType(held);
        if (type.isEmpty() || !(held.getItem() instanceof BlockItem item)) {
            return false;
        }
        BlockPlaceContext context = new BlockPlaceContext(player, hand, held, hit);
        if (!context.canPlace()) {
            return true;
        }
        BlockPos position = context.getClickedPos().immutable();
        if (runtime.portals().at(player.level(), position) != null) {
            return true;
        }
        if (item.useOn(context).consumesAction() && player.level().getBlockState(position).is(material(type.get()))) {
            runes.computeIfAbsent(player.level(), ignored -> new HashMap<>()).put(cell(position), new Rune(type.get()));
            player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.PORTAL_RUNE_PLACED, Map.of()), true);
            particles(player.level(), position, 16);
        }
        return true;
    }

    public boolean attackBlock(ServerPlayer player, BlockPos position) {
        runtime.requireServerThread();
        Map<PortalConstruction.Cell, Rune> placed = runes.get(player.level());
        Rune clicked = placed == null ? null : placed.get(cell(position));
        if (clicked == null || !MinecraftPortalTools.isWand(player.getMainHandItem())) {
            return false;
        }
        if (!runtime.access().permission(player, "wormholes.portals." + clicked.type.name().toLowerCase(Locale.ROOT))) {
            player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.COMMAND_NO_PERMISSION, Map.of()), true);
            return true;
        }
        Set<PortalConstruction.Cell> connected = PortalConstruction.connectedCells(cell(position), clicked.type,
            point -> placed.containsKey(point) ? placed.get(point).type : null);
        Map<BlockPos, BlockState> originals = new HashMap<>();
        for (PortalConstruction.Cell point : connected) {
            BlockPos block = position(point);
            if (!player.level().hasChunk(point.x() >> 4, point.z() >> 4)
                || !player.level().getBlockState(block).is(material(clicked.type))) {
                player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.PORTAL_FORM_INTERRUPTED, Map.of()), true);
                return true;
            }
            originals.put(block, player.level().getBlockState(block));
        }
        if (!coplanar(connected)) {
            player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.PORTAL_MUST_BE_FLAT, Map.of()), true);
            return true;
        }
        if (!runtime.access().canConstruct(player) || !runtime.access().canPlace(new MinecraftAccessService.Placement(
            player, player.level(), List.copyOf(originals.keySet()), MinecraftAccessService.PlacementKind.RUNE))) {
            return true;
        }
        ServerLevel level = player.level();
        try {
            for (BlockPos block : originals.keySet()) {
                if (!level.setBlock(block, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS)) {
                    throw new IllegalStateException("Could not consume rune at " + block);
                }
            }
            MinecraftPortal portal = runtime.portals().create(player.getUUID(), level, originals.keySet(), clicked.type, player.getLookAngle());
            runtime.effects().created(portal, originals);
        } catch (RuntimeException failure) {
            for (Map.Entry<BlockPos, BlockState> original : originals.entrySet()) {
                level.setBlock(original.getKey(), original.getValue(), Block.UPDATE_CLIENTS);
            }
            LOGGER.error("Could not construct {} rune aperture at {} in {}", clicked.type, position, level.dimension().identifier(), failure);
            player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.PORTAL_FORM_INTERRUPTED, Map.of()), true);
            return true;
        }
        for (PortalConstruction.Cell point : connected) {
            placed.remove(point);
            particles(level, position(point), 8);
        }
        player.sendSystemMessage(MinecraftMenuText.text(player, WormholesMessages.PORTAL_OPENED, Map.of()), true);
        return true;
    }

    public boolean beforeBreak(ServerPlayer player, BlockPos position) {
        runtime.requireServerThread();
        Map<PortalConstruction.Cell, Rune> placed = runes.get(player.level());
        Rune rune = placed == null ? null : placed.get(cell(position));
        if (rune == null) {
            return false;
        }
        if (MinecraftPortalTools.isWand(player.getMainHandItem())) {
            return true;
        }
        placed.remove(cell(position));
        if (player.level().getBlockState(position).is(material(rune.type))) {
            player.level().setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            if (player.gameMode() == GameType.SURVIVAL) {
                Block.popResource(player.level(), position, MinecraftPortalItems.of(runtime).rune(rune.type));
            }
            particles(player.level(), position, 12);
        }
        return true;
    }

    public void tick() {
        runtime.requireServerThread();
        vanilla.tick();
        if (++tick % 9 != 0) {
            return;
        }
        for (Map.Entry<ServerLevel, Map<PortalConstruction.Cell, Rune>> world : runes.entrySet()) {
            ServerLevel level = world.getKey();
            Iterator<Map.Entry<PortalConstruction.Cell, Rune>> entries = world.getValue().entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<PortalConstruction.Cell, Rune> entry = entries.next();
                PortalConstruction.Cell point = entry.getKey();
                Rune rune = entry.getValue();
                if (!level.hasChunk(point.x() >> 4, point.z() >> 4)) {
                    rune.unloaded = true;
                    continue;
                }
                BlockPos position = position(point);
                if (rune.unloaded || !level.getBlockState(position).is(material(rune.type))) {
                    if (rune.unloaded && level.getBlockState(position).is(material(rune.type))) {
                        level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    entries.remove();
                    continue;
                }
                if (runtime.configuration().settings().getMain().enableParticles && level.getRandom().nextDouble() < 0.35) {
                    particles(level, position, 1);
                }
            }
        }
    }

    @Override
    public void close() {
        vanilla.close();
        SERVICES.remove(server, this);
        server = null;
        runes.clear();
    }

    static Optional<PortalType> runeType(ItemStack item) {
        String type = item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getStringOr("wormholes:rune", "");
        if (type.equals("PORTAL") && item.is(Items.PRISMARINE)) {
            return Optional.of(PortalType.PORTAL);
        }
        return type.equals("WORMHOLE") && item.is(Items.DARK_PRISMARINE) ? Optional.of(PortalType.WORMHOLE) : Optional.empty();
    }

    private static boolean coplanar(Set<PortalConstruction.Cell> cells) {
        PortalConstruction.Cell first = cells.iterator().next();
        boolean flatX = true;
        boolean flatY = true;
        boolean flatZ = true;
        for (PortalConstruction.Cell cell : cells) {
            flatX &= first.x() == cell.x();
            flatY &= first.y() == cell.y();
            flatZ &= first.z() == cell.z();
        }
        return flatX || flatY || flatZ;
    }

    private void particles(ServerLevel level, BlockPos position, int count) {
        if (runtime.configuration().settings().getMain().enableParticles) {
            level.sendParticles(ParticleTypes.PORTAL, position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5,
                count, 0.35, 0.35, 0.35, 0.05);
        }
    }

    private static Block material(PortalType type) {
        return type == PortalType.PORTAL ? Blocks.PRISMARINE : Blocks.DARK_PRISMARINE;
    }

    private static PortalConstruction.Cell cell(BlockPos position) {
        return new PortalConstruction.Cell(position.getX(), position.getY(), position.getZ());
    }

    private static BlockPos position(PortalConstruction.Cell cell) {
        return new BlockPos(cell.x(), cell.y(), cell.z());
    }

    private static final class Rune {
        private final PortalType type;
        private boolean unloaded;

        private Rune(PortalType type) {
            this.type = type;
        }
    }
}
