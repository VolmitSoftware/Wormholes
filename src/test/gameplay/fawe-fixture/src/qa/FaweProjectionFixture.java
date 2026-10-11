package qa;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.service.WormholesTelemetry;
import art.arcane.wormholes.render.clientview.BukkitPortalEnvironment;
import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.environment.WorldEnvironmentAccess;
import art.arcane.wormholes.libs.packetevents.api.PacketEvents;
import art.arcane.wormholes.libs.packetevents.api.util.Vector3i;
import art.arcane.wormholes.libs.packetevents.api.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import art.arcane.wormholes.libs.packetevents.impl.util.SpigotConversionUtil;
import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.integration.ReloadPreparation;
import com.fastasyncworldedit.core.internal.exception.FaweException;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.GameRule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class FaweProjectionFixture extends JavaPlugin {
    private LocalPortal source;
    private LocalPortal target;
    private World world;
    private CompletableFuture<Void> nativeAdmitted;
    private CompletableFuture<Void> nativeReleased;
    private CompletableFuture<Void> nativeFinished;
    private CompletableFuture<ReloadPreparation> nativePreparation;

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp() || args.length == 0) {
            return true;
        }
        try {
            switch (args[0]) {
                case "setup" -> setup(player);
                case "stage" -> stage(player);
                case "sample" -> sample(player, args.length > 1 ? Long.parseLong(args[1]) : -1L);
                case "environment" -> environment(player);
                case "single" -> single(player);
                case "section" -> section(player);
                case "chunk" -> refreshChunk(player);
                case "native-start" -> startNative(player, args.length < 2 || !args[1].equals("noops"));
                case "native-status" -> nativeStatus(player);
                case "native-prepare" -> prepareNative(player);
                case "native-release" -> releaseNative(player);
                case "release" -> release(player);
                case "cleanup" -> cleanup(player);
                default -> throw new IllegalArgumentException("Unknown FAWE fixture action");
            }
        } catch (RuntimeException error) {
            getLogger().log(Level.SEVERE, "FAWE projection fixture action failed: " + args[0], error);
            player.sendMessage("WHFAWE failure=" + error.getClass().getSimpleName());
        }
        return true;
    }

    private void setup(Player player) {
        if (getServer().getName().contains("Folia")) {
            throw new IllegalStateException("FAWE fixture requires Paper or Spigot single-thread ownership");
        }
        if (source != null || target != null) {
            throw new IllegalStateException("FAWE fixture already has portals; clean up before another run");
        }
        world = player.getWorld();
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
        world.setTime(6000L);
        world.setStorm(false);
        world.setThundering(false);
        for (int chunkX = -1; chunkX <= 7; chunkX++) {
            for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
                world.addPluginChunkTicket(chunkX, chunkZ, this);
            }
        }
        prepare(0);
        prepare(96);
        world.getBlockAt(8, 193, 4).setType(Material.STONE, false);
        world.getBlockAt(9, 193, 4).setType(Material.STONE, false);
        world.getBlockAt(2, 193, 13).setType(Material.IRON_BLOCK, false);
        world.getBlockAt(104, 193, 4).setType(Material.DIAMOND_BLOCK, false);
        world.getBlockAt(105, 193, 4).setType(Material.EMERALD_BLOCK, false);
        source = portal(player.getUniqueId(), 0, "source");
        target = portal(player.getUniqueId(), 96, "target");
        if (!source.setDestination(target)) {
            throw new IllegalStateException("FAWE fixture portal linking failed");
        }
        source.setProjectionMode(ProjectionMode.ON);
        source.save();
        Wormholes.projectionChangeTracker.markChanged(world.getUID(), 0, 0);
        Wormholes.projectionChangeTracker.markChanged(world.getUID(), 96, 0);
        Plugin fawe = getServer().getPluginManager().getPlugin("FastAsyncWorldEdit");
        player.sendMessage("WHFAWE ready open=" + source.isOpen() + " linked=" + source.hasTunnel()
            + " fawe=" + fawe.getDescription().getVersion());
    }

    private void prepare(int offset) {
        for (int x = 0; x <= 15; x++) {
            for (int z = 0; z <= 15; z++) {
                for (int y = 190; y <= 199; y++) {
                    world.getBlockAt(offset + x, y, z).setType(y == 190 ? Material.STONE : Material.AIR, false);
                }
            }
        }
    }

    private LocalPortal portal(UUID owner, int offset, String name) {
        Set<Block> aperture = new HashSet<Block>(30);
        for (int x = 5; x <= 11; x++) {
            for (int y = 190; y <= 197; y++) {
                Block block = world.getBlockAt(offset + x, y, 8);
                if (x == 5 || x == 11 || y == 190 || y == 197) {
                    block.setType(Material.OBSIDIAN, false);
                } else {
                    aperture.add(block);
                }
            }
        }
        PortalStructure structure = new PortalStructure();
        structure.setBlocks(aperture);
        LocalPortal portal = new LocalPortal(UUID.randomUUID(), PortalType.PORTAL, structure);
        portal.setName("FAWE fixture " + name);
        portal.setOwner(owner);
        portal.setFrame(Frame.canonical(Face.S));
        portal.setNetworkViewDepth(16);
        portal.setNetworkViewLateralPad(8);
        portal.setRenderMode(ProjectionRenderMode.VENTICULAR);
        portal.setProjectionMode(ProjectionMode.OFF);
        Wormholes.portalManager.addLocalPortal(portal);
        portal.open();
        portal.save();
        return portal;
    }

    private void stage(Player player) {
        boolean done = player.teleport(new Location(world, 8.5, 191, 13.5, 180, 0));
        player.sendMessage("WHFAWE staged=" + done);
    }

    private void environment(Player player) {
        EnvironmentState state = BukkitPortalEnvironment.capture(world, new Vec3d(104.5D, 193.5D, 4.5D),
            OpticTransform.IDENTITY);
        if (state.dimension().minY() != world.getMinHeight()
            || state.dimension().height() != world.getMaxHeight() - world.getMinHeight()
            || state.world().logicalHeight() <= 0 || state.world().eyeMedium() != EnvironmentState.EyeMedium.NONE) {
            throw new IllegalStateException("Destination environment does not match its loaded world");
        }
        String provider = NativeAdapters.require(WorldEnvironmentAccess.class).getClass().getName();
        getLogger().info("Destination environment captured: provider=" + provider + " height=" + state.dimension().height()
            + " sky=" + state.sky().skybox() + " medium=" + state.world().eyeMedium());
        player.sendMessage("WHFAWE environment provider=" + provider + " height=" + state.dimension().height()
            + " sky=" + state.sky().skybox() + " medium=" + state.world().eyeMedium());
    }

    private void sample(Player player, long since) {
        player.sendMessage("WHFAWE sample version=" + Wormholes.projectionChangeTracker.currentVersion()
            + " targetDirty=" + Wormholes.projectionChangeTracker.dirtySince(world.getUID(), 6, 0, 6, 0, since)
            + " local=" + world.getBlockAt(8, 193, 4).getType()
            + " neighbor=" + world.getBlockAt(9, 193, 4).getType()
            + " unrelated=" + world.getBlockAt(2, 193, 13).getType()
            + " destination=" + world.getBlockAt(104, 193, 4).getType()
            + " destinationSnapshot=" + world.getChunkAt(6, 0).getChunkSnapshot().getBlockType(8, 193, 4)
            + " destinationNeighbor=" + world.getBlockAt(105, 193, 4).getType()
            + " observer=" + Wormholes.projectionManager.observersOf(source.getId()).contains(player)
            + " failures=" + WormholesTelemetry.failures()
            + " folder=" + Base64.getUrlEncoder().withoutPadding().encodeToString(
                world.getWorldFolder().getAbsolutePath().getBytes(StandardCharsets.UTF_8)));
    }

    private void single(Player player) {
        Block block = world.getBlockAt(8, 193, 4);
        block.setType(Material.GOLD_BLOCK, false);
        player.sendBlockChange(block.getLocation(), block.getBlockData());
        player.sendMessage("WHFAWE single real=GOLD_BLOCK");
    }

    private void section(Player player) {
        Block unrelated = world.getBlockAt(2, 193, 13);
        unrelated.setType(Material.COPPER_BLOCK, false);
        WrapperPlayServerMultiBlockChange.EncodedBlock[] terrain =
            new WrapperPlayServerMultiBlockChange.EncodedBlock[3];
        int[] positions = { 8, 9, 2 };
        for (int index = 0; index < positions.length; index++) {
            Block block = world.getBlockAt(positions[index], 193, index == 2 ? 13 : 4);
            terrain[index] = new WrapperPlayServerMultiBlockChange.EncodedBlock(
                SpigotConversionUtil.fromBukkitBlockData(block.getBlockData()).getGlobalId(),
                block.getX(), block.getY(), block.getZ());
        }
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
            new WrapperPlayServerMultiBlockChange(new Vector3i(0, 12, 0), false, terrain));
        player.sendMessage("WHFAWE section real=GOLD_BLOCK neighbor=STONE unrelated=COPPER_BLOCK");
    }

    private void refreshChunk(Player player) {
        if (!world.refreshChunk(0, 0)) {
            throw new IllegalStateException("Server refused the source full-chunk refresh");
        }
        player.sendMessage("WHFAWE chunk requested=true");
    }

    private void release(Player player) {
        source.setProjectionMode(ProjectionMode.OFF);
        source.save();
        player.sendMessage("WHFAWE released=true");
    }

    private void startNative(Player player, boolean reset) {
        if (nativeFinished != null && !nativeFinished.isDone()) {
            throw new IllegalStateException("A native edit is already active");
        }
        BukkitWorld destination = new BukkitWorld(world);
        if (reset) {
            for (int index = 0; index < 1023; index++) {
                world.getBlockAt(112 + (index & 15), 200 + (index >> 8), (index >> 4) & 15).setType(Material.AIR, false);
            }
        }
        nativeAdmitted = new CompletableFuture<>();
        nativeReleased = new CompletableFuture<>();
        nativeFinished = CompletableFuture.runAsync(() -> applyNative(destination));
        nativeFinished.whenComplete((ignored, failure) -> {
            if (failure != null) {
                nativeAdmitted.completeExceptionally(failure);
                getLogger().log(Level.SEVERE, "Native FAWE fixture edit failed", failure);
            }
        });
        player.sendMessage("WHFAWE native-start async=true writes=1024");
    }

    private void applyNative(BukkitWorld destination) {
        if (Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("The native FAWE admission must run asynchronously");
        }
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder()
                .world(destination).maxBlocks(-1).fastMode(true).forceWNA().build()) {
            session.setBlock(BlockVector3.at(104, 193, 4), BlockTypes.GOLD_BLOCK.getDefaultState());
            for (int index = 0; index < 1023; index++) {
                session.setBlock(BlockVector3.at(112 + (index & 15), 200 + (index >> 8), (index >> 4) & 15),
                        BlockTypes.GOLD_BLOCK.getDefaultState());
            }
            nativeAdmitted.complete(null);
            nativeReleased.orTimeout(30, TimeUnit.SECONDS).join();
            if (!session.cancel()) {
                throw new IllegalStateException("The active native edit did not cancel");
            }
        } catch (FaweException exception) {
            if (exception.getType() != FaweException.Type.MANUAL || !nativeAdmitted.isDone()
                    || exception.getSuppressed().length != 0) {
                throw exception;
            }
            getLogger().log(Level.INFO, "Native FAWE edit reported its expected manual cancellation", exception);
        } catch (WorldEditException exception) {
            throw new CompletionException(exception);
        }
    }

    private void nativeStatus(Player player) {
        if (nativeAdmitted == null) {
            throw new IllegalStateException("No native edit was started");
        }
        if (nativeAdmitted.isCompletedExceptionally()) {
            nativeAdmitted.join();
        }
        player.sendMessage("WHFAWE native-status admitted=" + nativeAdmitted.isDone());
    }

    private void prepareNative(Player player) {
        if (nativeAdmitted == null || !nativeAdmitted.isDone() || nativeFinished.isDone()) {
            throw new IllegalStateException("The native edit must remain admitted and open");
        }
        nativeAdmitted.join();
        nativePreparation = JavaPlugin.getPlugin(Wormholes.class).prepareReload(ReloadAware.PreUnloadReason.HOT_RELOAD)
                .toCompletableFuture();
        if (nativePreparation.isDone()) {
            throw new IllegalStateException("Reload preparation did not wait for the admitted native edit");
        }
        player.sendMessage("WHFAWE native-prepare waiting=true");
    }

    private void releaseNative(Player player) {
        if (nativePreparation == null) {
            throw new IllegalStateException("Reload preparation must begin before releasing the native edit");
        }
        nativeReleased.complete(null);
        nativeFinished.thenCombine(nativePreparation, (ignored, preparation) -> preparation)
                .whenComplete((preparation, failure) -> getServer().getScheduler().runTask(this,
                        () -> verifyNative(player, preparation, failure)));
    }

    private void verifyNative(Player player, ReloadPreparation preparation, Throwable failure) {
        try {
            if (failure != null) {
                throw new CompletionException(failure);
            }
            if (!preparation.ready()) {
                throw new IllegalStateException("Reload preparation refused the completed edit");
            }
            if (world.getBlockAt(104, 193, 4).getType() != Material.GOLD_BLOCK) {
                throw new IllegalStateException("Cancelled native marker was not applied");
            }
            for (int index = 0; index < 1023; index++) {
                if (world.getBlockAt(112 + (index & 15), 200 + (index >> 8), (index >> 4) & 15)
                        .getType() != Material.GOLD_BLOCK) {
                    throw new IllegalStateException("Detached native batch did not apply cell " + index);
                }
            }
            player.sendMessage("WHFAWE native-release ready=true applied=1024 cancelled=true");
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Native FAWE fixture acceptance failed", exception);
            player.sendMessage("WHFAWE failure=" + exception.getClass().getSimpleName());
        } finally {
            JavaPlugin.getPlugin(Wormholes.class).cancelReload();
        }
    }

    private void cleanup(Player player) {
        if (nativeReleased != null) {
            nativeReleased.complete(null);
        }
        JavaPlugin.getPlugin(Wormholes.class).cancelReload();
        if (source != null) {
            source.destroy();
            source = null;
        }
        if (target != null) {
            target.destroy();
            target = null;
        }
        if (world != null) {
            for (int chunkX = -1; chunkX <= 7; chunkX++) {
                for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
                    world.removePluginChunkTicket(chunkX, chunkZ, this);
                }
            }
        }
        player.sendMessage("WHFAWE cleaned=true");
    }
}
