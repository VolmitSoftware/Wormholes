package qa;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.rtp.RtpAllocationMode;
import art.arcane.wormholes.portal.rtp.RtpCenterMode;
import art.arcane.wormholes.portal.rtp.RtpDestination;
import art.arcane.wormholes.portal.rtp.RtpRotationMode;
import art.arcane.wormholes.portal.rtp.RtpRuntimeSnapshot;
import art.arcane.wormholes.portal.rtp.RtpSafetyMode;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.portal.rtp.RtpVerticalMode;
import art.arcane.wormholes.util.Direction;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class PlaceholderFixture extends JavaPlugin {
    private static final UUID SOURCE = UUID.fromString("90000000-0000-0000-0000-000000000001");
    private static final UUID DESTINATION = UUID.fromString("90000000-0000-0000-0000-000000000002");
    private static final UUID RTP = UUID.fromString("90000000-0000-0000-0000-000000000003");
    private static final UUID UNLINKED = UUID.fromString("90000000-0000-0000-0000-000000000004");
    private static final UUID FIRST_TWIN = UUID.fromString("90000000-0000-0000-0000-000000000005");
    private static final UUID SECOND_TWIN = UUID.fromString("90000000-0000-0000-0000-000000000006");
    private static final List<UUID> PORTALS = List.of(SOURCE, DESTINATION, RTP, UNLINKED, FIRST_TWIN, SECOND_TWIN);

    private final RtpRetirementFixture retirement = new RtpRetirementFixture(this, RTP);
    private volatile ScheduledTask attendance;
    private volatile World fixtureWorld;

    @Override
    public void onDisable() {
        stopAttendance();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || !player.isOp() || args.length != 1) {
            return true;
        }
        switch (args[0]) {
            case "setup" -> {
                Setup request = new Setup(player.getWorld(), player.getUniqueId(), player);
                getServer().getRegionScheduler().execute(this, request.world(), -1, -1, () -> setup(request));
            }
            case "rtp" -> reportRtp(player);
            case "retire-mob" -> region(player, () -> retirement.start(player, true));
            case "followup-mob" -> region(player, () -> retirement.start(player, false));
            case "retirement-status" -> region(player, () -> retirement.report(player));
            case "retirement-check" -> region(player, () -> retirement.verify(player));
            case "rename" -> region(player, () -> {
                requirePortal(SOURCE).setName("Changed Gate??");
                requirePortal(SOURCE).save();
                reply(player, "FIXTURE renamed");
            });
            case "cleanup" -> region(player, () -> cleanup(player));
            default -> player.sendMessage("FIXTURE unknown command");
        }
        return true;
    }

    private void setup(Setup request) {
        try {
            fixtureWorld = request.world();
            for (UUID id : PORTALS) {
                if (Wormholes.portalManager.getLocalPortal(id) != null) {
                    throw new IllegalStateException("Fixture portal already exists: " + id);
                }
            }
            for (int x = -16; x < 0; x++) {
                for (int z = -16; z < 0; z++) {
                    fixtureWorld.getBlockAt(x, 100, z).setType(Material.STONE, false);
                    for (int y = 101; y <= 107; y++) {
                        fixtureWorld.getBlockAt(x, y, z).setType(Material.AIR, false);
                    }
                }
            }
            LocalPortal source = create(request, new PortalSpec(SOURCE, "North Gate!!", PortalType.GATEWAY, -14, 100, -14));
            LocalPortal destination = create(request, new PortalSpec(DESTINATION, "Destination", PortalType.GATEWAY, -6, 80, -6));
            LocalPortal rtp = create(request, new PortalSpec(RTP, "Timed Gate", PortalType.RTP, -14, 100, -6));
            create(request, new PortalSpec(UNLINKED, "Unlinked Gate", PortalType.GATEWAY, -6, 100, -14));
            LocalPortal firstTwin = create(request, new PortalSpec(FIRST_TWIN, "Twin Gate", PortalType.GATEWAY, -6, 120, -14));
            LocalPortal secondTwin = create(request, new PortalSpec(SECOND_TWIN, "Twin--Gate", PortalType.GATEWAY, -6, 140, -14));
            if (!source.setDestination(destination) || !firstTwin.setDestination(destination) || !secondTwin.setDestination(source)) {
                throw new IllegalStateException("Could not link fixture portals");
            }
            request.recipient().getScheduler().execute(this, () -> stage(request.recipient(), rtp), null, 1L);
        } catch (RuntimeException failure) {
            fail(request.recipient(), failure);
        }
    }

    private LocalPortal create(Setup request, PortalSpec spec) {
        Set<Block> aperture = new HashSet<>(12);
        for (int x = spec.x() - 1; x <= spec.x() + 3; x++) {
            for (int y = spec.floorY(); y <= spec.floorY() + 5; y++) {
                Block block = request.world().getBlockAt(x, y, spec.z());
                boolean border = x == spec.x() - 1 || x == spec.x() + 3 || y == spec.floorY() || y == spec.floorY() + 5;
                block.setType(border ? Material.OBSIDIAN : Material.AIR, false);
                if (!border) {
                    aperture.add(block);
                }
            }
        }
        PortalStructure structure = new PortalStructure();
        structure.setBlocks(aperture);
        LocalPortal portal = new LocalPortal(spec.id(), spec.type(), structure);
        portal.setName(spec.name());
        portal.setFrame(PortalFrame.derive(structure.getArea(), Direction.S));
        portal.setOwner(request.owner());
        portal.setProjectionMode(ProjectionMode.OFF);
        if (spec.type() == PortalType.RTP) {
            JSONObject state = portal.toJSON();
            state.put("rtp", timedSettings(request.world()).toJson());
            portal.loadJSON(state);
        }
        Wormholes.portalManager.addLocalPortal(portal);
        portal.open();
        portal.save();
        return portal;
    }

    private RtpSettings timedSettings(World world) {
        return RtpSettings.builder(world)
            .centerMode(RtpCenterMode.CUSTOM)
            .customCenter(-8.0D, -9.0D)
            .radii(0, 2)
            .verticalMode(RtpVerticalMode.PREFERRED_AVERAGE)
            .safetyMode(RtpSafetyMode.SAFE)
            .yBounds(101, 101)
            .preferredY(101)
            .allocationMode(RtpAllocationMode.SHARED)
            .rotationMode(RtpRotationMode.TIMED)
            .cycleDurationMillis(3_665_000L)
            .leaseIdleMillis(60_000L)
            .rimEnabled(false)
            .soundEnabled(false)
            .build();
    }

    private void stage(Player player, LocalPortal rtp) {
        player.teleportAsync(new Location(fixtureWorld, -8.5D, 101.0D, -8.5D, 90.0F, 0.0F))
            .whenComplete((moved, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(moved)) {
                    fail(player, failure == null ? new IllegalStateException("Fixture teleport failed") : failure);
                    return;
                }
                stopAttendance();
                attendance = player.getScheduler().runAtFixedRate(this, ignored -> Wormholes.rtpRuntime.touch(rtp, player),
                    null, 1L, 10L);
                if (attendance == null) {
                    fail(player, new IllegalStateException("Could not keep the RTP viewer active"));
                    return;
                }
                reply(player, "FIXTURE ready source=" + SOURCE + " destination=" + DESTINATION + " rtp=" + RTP);
            });
    }

    private void reportRtp(Player player) {
        RtpService.Snapshot snapshot = Wormholes.rtpRuntime.snapshotOrNull(RTP);
        if (snapshot == null || snapshot.runtime().active() == null || !snapshot.runtime().ready()) {
            player.sendMessage("FIXTURE rtp ready=false");
            return;
        }
        RtpRuntimeSnapshot runtime = snapshot.runtime();
        RtpDestination destination = runtime.active();
        player.sendMessage("FIXTURE rtp ready=true x=" + destination.blockX() + " y=" + destination.feetY()
            + " z=" + destination.blockZ() + " next=" + runtime.nextRotationAtMillis() + " now=" + System.currentTimeMillis());
    }

    private void cleanup(Player player) {
        stopAttendance();
        retirement.cleanup().whenComplete((ignored, failure) -> finishMobCleanup(player, failure));
    }

    private void finishMobCleanup(Player player, Throwable failure) {
        if (failure != null) {
            fail(player, failure);
            return;
        }
        region(player, () -> cleanupPortals(player));
    }

    private void cleanupPortals(Player player) {
        for (UUID id : PORTALS) {
            ILocalPortal portal = Wormholes.portalManager.getLocalPortal(id);
            if (portal != null) {
                portal.destroy();
            }
        }
        reply(player, "FIXTURE cleaned");
    }

    private void stopAttendance() {
        ScheduledTask task = attendance;
        attendance = null;
        if (task != null) {
            task.cancel();
        }
    }

    private LocalPortal requirePortal(UUID id) {
        ILocalPortal portal = Wormholes.portalManager.getLocalPortal(id);
        if (!(portal instanceof LocalPortal local)) {
            throw new IllegalStateException("Missing fixture portal " + id);
        }
        return local;
    }

    private void region(Player player, Runnable action) {
        if (fixtureWorld == null) {
            player.sendMessage("FIXTURE setup required");
            return;
        }
        getServer().getRegionScheduler().execute(this, fixtureWorld, -1, -1, () -> {
            try {
                action.run();
            } catch (RuntimeException failure) {
                fail(player, failure);
            }
        });
    }

    private void reply(Player player, String message) {
        player.getScheduler().execute(this, () -> player.sendMessage(message), null, 1L);
    }

    private void fail(Player player, Throwable failure) {
        getLogger().log(Level.SEVERE, "Placeholder fixture failed", failure);
        reply(player, "FIXTURE failed " + failure.getClass().getSimpleName());
    }

    private record Setup(World world, UUID owner, Player recipient) {
    }

    private record PortalSpec(UUID id, String name, PortalType type, int x, int floorY, int z) {
    }
}
