package art.arcane.wormholes.demo;

import art.arcane.wormholes.Wormholes;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.nexus.FrameIo;
import art.arcane.wormholes.nexus.NetworkMember;
import art.arcane.wormholes.nexus.NetworkRegistry;
import art.arcane.wormholes.nexus.NexusPortalExtension;
import art.arcane.wormholes.nexus.NexusSubsystem;
import art.arcane.wormholes.nexus.PortalNetwork;
import art.arcane.wormholes.nexus.Visibility;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.NetworkPairingService;
import art.arcane.wormholes.network.TraversalService;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.portal.rtp.RtpDestination;
import art.arcane.wormholes.portal.rtp.RtpPortalRuntime;
import art.arcane.wormholes.portal.vanilla.PortalFactory;
import art.arcane.wormholes.render.FidelityPortalExtension;
import art.arcane.wormholes.transit.TransitPortalExtension;
import art.arcane.wormholes.util.Direction;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Switch;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class FeatureScenes {
    private static final UUID NETWORK_ID = UUID.nameUUIDFromBytes("wormholes-demo-garden-routes".getBytes(StandardCharsets.UTF_8));
    private static final String LIVE_ENTITY_TAG = "wormholes-demo-live";
    private static final List<String> SCENES = List.of("linked", "mirror", "hills", "orientation", "particles", "atmosphere", "transit", "arrival", "rtp", "nexus", "nested", "gateway", "remote-gateway", "live");

    private final World world;

    public FeatureScenes(World world) {
        this.world = Objects.requireNonNull(world);
    }

    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("feature requires prepare <scene>, report, freeze <seconds>, server-export, network-report <player>, or remote-link <portal> <server> <destination-id>");
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "prepare" -> {
                if (args.length != 3 || !SCENES.contains(args[2].toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("feature prepare requires " + String.join("|", SCENES));
                }
                prepare(sender, args[2].toLowerCase(Locale.ROOT));
            }
            case "report" -> report(sender);
            case "freeze" -> freeze(sender, args);
            case "server-export" -> sender.sendMessage("serverCode=" + new NetworkPairingService(runningNetwork()).serverCode().encode());
            case "network-report" -> {
                if (args.length != 3) {
                    throw new IllegalArgumentException("feature network-report requires a player name");
                }
                networkReport(sender, args[2]);
            }
            case "remote-link" -> remoteLink(sender, args);
            default -> throw new IllegalArgumentException("Unknown feature operation: " + args[1]);
        }
    }

    private void freeze(CommandSender sender, String[] args) {
        if (args.length != 3) {
            throw new IllegalArgumentException("feature freeze requires 0 or 5..300 seconds");
        }
        int seconds = Integer.parseInt(args[2]);
        if (seconds != 0 && (seconds < 5 || seconds > 300)) {
            throw new IllegalArgumentException("feature freeze requires 0 or 5..300 seconds");
        }
        if (Wormholes.projectionManager == null) {
            throw new IllegalStateException("Projection manager is unavailable");
        }
        sender.sendMessage("frozenUntil=" + Wormholes.projectionManager.freezeProjections(seconds * 1000L));
    }

    private void prepare(CommandSender sender, String scene) {
        clearNetwork();
        int removed = Wormholes.portalManager.deleteAllPortals();
        for (Entity entity : world.getEntities()) {
            if (entity.getScoreboardTags().contains(LIVE_ENTITY_TAG)) {
                entity.remove();
            }
        }
        if (Wormholes.projectionManager != null) {
            Wormholes.projectionManager.freezeProjections(0L);
        }
        new DemoScene(world).build(true);
        clearAperture(0);
        clearAperture(200);
        landmarks(200);
        biome(0, Biome.PLAINS);
        biome(200, scene.equals("atmosphere") ? Biome.SWAMP : Biome.DESERT);
        if (scene.equals("live")) {
            liveScene();
        }
        if (scene.equals("hills")) {
            hills();
        }
        PortalType type = scene.equals("gateway") || scene.equals("remote-gateway") ? PortalType.GATEWAY : PortalType.PORTAL;
        LocalPortal source = create(0, "Garden Arch", scene.equals("rtp") ? PortalType.RTP : type);
        FeatureScenes destinationScene = scene.equals("atmosphere") || scene.equals("gateway") ? destinationScene(scene) : this;
        LocalPortal destination = scene.equals("arrival") ? createSidePortal() : destinationScene.create(200, "Sun Court", type);
        if (scene.equals("atmosphere")) {
            source.setRenderMode(ProjectionRenderMode.PANOPTIC);
            source.setAmbientStyle(AmbientParticleStyle.OFF);
            destination.setAmbientStyle(AmbientParticleStyle.OFF);
        }
        if (scene.equals("hills")) {
            source.setNetworkViewDepth(24);
            source.setNetworkViewLateralPad(8);
            destination.setNetworkViewDepth(24);
            destination.setNetworkViewLateralPad(8);
        }
        if (scene.equals("mirror")) {
            source.setMirrorMode(true);
        } else if (scene.equals("rtp")) {
            JSONObject document = source.toJSON();
            JSONObject settings = document.getJSONObject("rtp");
            settings.put("centerMode", "CUSTOM");
            settings.put("customCenterX", 0.5);
            settings.put("customCenterZ", 200.5);
            settings.put("minimumRadius", 6);
            settings.put("maximumRadius", 16);
            settings.put("lowerY", 70);
            settings.put("upperY", 80);
            settings.put("preferredY", 70);
            source.loadJSON(document);
            source.save();
            Wormholes.rtpRuntime.synchronize(source);
        } else {
            link(source, destination);
        }
        if (scene.equals("nested")) {
            terrace();
            frame(188, Material.PRISMARINE_BRICKS);
            LocalPortal inner = create(188, "Courtyard Window", PortalType.PORTAL);
            LocalPortal distant = create(400, "Amber Terrace", PortalType.PORTAL);
            link(inner, distant);
        }
        if (scene.equals("nexus")) {
            terrace();
            LocalPortal distant = create(400, "Amber Terrace", PortalType.PORTAL);
            linkNetwork(source, destination, distant);
        }
        for (ILocalPortal portal : Wormholes.portalManager.getLocalPortals()) {
            if (fixtureWorld(portal.getWorld())) {
                portal.update();
                portal.save();
            }
        }
        sender.sendMessage("Feature scene ready: scene=" + scene + ", removed=" + removed
                + ", source=0.5,71.5,0.5, destination=0.5,71.5,200.5, markers=0, sourceWorld=" + world.getName()
                + ", destinationWorld=" + destination.getWorld().getName());
        if (scene.equals("hills")) {
            sender.sendMessage("terrain=x:-12..12,z:176..195,y:69..79; ridges=3; frontCrest=0,79,192; rearCrest=-1,75,181; shadowLandmark=0,76..78,181; viewDepth=24; lateralPad=8; sourceApproach=0.5,70,3.8; behindFrame=7.5,72,-9.5");
        }
        if (scene.equals("nexus")) {
            sender.sendMessage("network=GardenRoutes, addresses=GARDEN|SUN|AMBER, lever=-4,71,3, redstone=NONE, third=0.5,71.5,400.5");
        }
        if (scene.equals("nested")) {
            sender.sendMessage("inner=0.5,71.5,188.5, innerDestination=0.5,71.5,400.5");
        }
        report(sender);
    }

    private NetworkManager runningNetwork() {
        NetworkManager network = Wormholes.networkManager;
        if (network == null || !network.isRunning()) {
            throw new IllegalStateException("Networking must be enabled and running before this command");
        }
        return network;
    }

    private void networkReport(CommandSender sender, String playerName) {
        NetworkManager network = Wormholes.networkManager;
        JsonObject result = new JsonObject();
        result.addProperty("server", network == null ? "" : network.getLocalName());
        result.addProperty("running", network != null && network.isRunning());
        result.addProperty("listenPort", network == null ? 0 : network.getBoundListenPort());
        result.addProperty("onlineMode", Bukkit.getOnlineMode());
        JsonArray peers = new JsonArray();
        if (network != null) {
            for (NetworkManager.PeerStatus peer : network.status()) {
                JsonObject state = new JsonObject();
                state.addProperty("name", peer.name());
                state.addProperty("ready", network.isPeerReady(peer.name()));
                state.addProperty("state", peer.state());
                state.addProperty("address", peer.address());
                state.addProperty("rttMillis", peer.rttMillis());
                state.addProperty("lastError", peer.lastError());
                peers.add(state);
            }
        }
        result.add("peers", peers);
        Player player = Bukkit.getPlayerExact(playerName);
        JsonObject actor = new JsonObject();
        actor.addProperty("name", playerName);
        actor.addProperty("online", player != null && player.isOnline());
        if (player != null && player.isOnline()) {
            Location location = player.getLocation();
            actor.addProperty("uuid", player.getUniqueId().toString());
            actor.addProperty("world", player.getWorld().getName());
            actor.addProperty("worldId", player.getWorld().getUID().toString());
            actor.addProperty("dimension", player.getWorld().getKey().toString());
            actor.addProperty("x", location.getX());
            actor.addProperty("y", location.getY());
            actor.addProperty("z", location.getZ());
            actor.addProperty("yaw", location.getYaw());
            actor.addProperty("pitch", location.getPitch());
            actor.addProperty("transferred", player.isTransferred());
        }
        result.add("player", actor);
        JsonObject counters = new JsonObject();
        TraversalService traversal = Wormholes.traversalService;
        counters.addProperty("available", traversal != null);
        if (traversal != null) {
            TraversalService.Stats stats = traversal.statsSnapshot();
            counters.addProperty("completed", stats.completed());
            counters.addProperty("failed", stats.failed());
            counters.addProperty("inFlight", stats.inFlight());
        }
        result.add("handoffs", counters);
        sender.sendMessage("networkReport=" + result);
    }

    private void remoteLink(CommandSender sender, String[] args) {
        if (args.length < 5) {
            throw new IllegalArgumentException("feature remote-link requires a local portal name or UUID, peer name, and destination UUID");
        }
        String localName = String.join(" ", Arrays.copyOfRange(args, 2, args.length - 2));
        String peer = args[args.length - 2];
        UUID destinationId = UUID.fromString(args[args.length - 1]);
        NetworkManager network = runningNetwork();
        if (!network.isPeerReady(peer)) {
            throw new IllegalStateException("Peer is not ready: " + peer);
        }
        if (Wormholes.remotePortalRegistry == null || Wormholes.remotePortalRegistry.get(peer, destinationId) == null) {
            throw new IllegalStateException("Destination portal is not in the ready peer directory");
        }
        ILocalPortal selected = null;
        for (ILocalPortal portal : Wormholes.portalManager.getLocalPortals()) {
            if (portal.getId().toString().equals(localName) || portal.getName().equals(localName)) {
                if (selected != null) {
                    throw new IllegalArgumentException("Portal name is ambiguous; use its UUID");
                }
                selected = portal;
            }
        }
        if (selected == null || !selected.isGateway()) {
            throw new IllegalArgumentException("Local gateway not found: " + localName);
        }
        if (!selected.linkRemote(peer, destinationId)) {
            throw new IllegalStateException("Gateway rejected the remote link");
        }
        selected.update();
        selected.save();
        sender.sendMessage("remoteLinkPortal=" + selected.getId() + ", server=" + peer + ", destination=" + destinationId
                + ", linked=" + (selected.getTunnel() != null && selected.getTunnel().isValid()) + ", open=" + selected.isOpen());
    }

    private LocalPortal create(int z, String name, PortalType type) {
        Set<Block> cells = new HashSet<Block>(9);
        for (int x = -1; x <= 1; x++) {
            for (int y = 70; y <= 72; y++) {
                cells.add(world.getBlockAt(x, y, z));
            }
        }
        return register(cells, PortalFrame.canonical(Direction.S), type, name);
    }

    private LocalPortal register(Set<Block> cells, PortalFrame frame, PortalType type, String name) {
        ILocalPortal created = PortalFactory.createFromCells(cells, frame, type, name);
        if (!(created instanceof LocalPortal portal)) {
            throw new IllegalStateException("Failed to prepare " + name);
        }
        portal.setSettingsSyncEnabled(false);
        portal.setActivationRange(48);
        portal.setNetworkViewDepth(32);
        portal.setNetworkViewLateralPad(16);
        return portal;
    }

    private LocalPortal createSidePortal() {
        for (int x = -2; x <= 2; x++) {
            for (int y = 70; y <= 73; y++) {
                block(x, y, 200, Material.AIR);
            }
        }
        Set<Block> cells = new HashSet<Block>(9);
        for (int z = 198; z <= 202; z++) {
            block(0, 69, z, Material.CUT_SANDSTONE);
            block(0, 73, z, Material.CUT_SANDSTONE);
            for (int y = 70; y <= 72; y++) {
                block(0, y, z, z == 198 || z == 202 ? Material.CUT_SANDSTONE : Material.AIR);
                if (z > 198 && z < 202) {
                    cells.add(world.getBlockAt(0, y, z));
                }
            }
        }
        return register(cells, PortalFrame.canonical(Direction.E), PortalType.PORTAL, "Sun Court");
    }

    private FeatureScenes destinationScene(String scene) {
        String name = world.getName() + "_demo_destination";
        World destination = Bukkit.getWorld(name);
        if (destination == null) {
            destination = new WorldCreator(name).type(WorldType.FLAT).generateStructures(false).seed(184730L).createWorld();
        }
        if (destination == null) {
            throw new IllegalStateException("Could not create destination world " + name);
        }
        new DemoScene(destination).build(true);
        FeatureScenes sceneBuilder = new FeatureScenes(destination);
        sceneBuilder.clearAperture(0);
        sceneBuilder.clearAperture(200);
        sceneBuilder.landmarks(200);
        sceneBuilder.biome(200, scene.equals("atmosphere") ? Biome.SWAMP : Biome.DESERT);
        if (scene.equals("atmosphere")) {
            sceneBuilder.wetland();
            destination.setTime(18000L);
            destination.setStorm(true);
            destination.setWeatherDuration(24000);
        }
        return sceneBuilder;
    }

    private boolean fixtureWorld(World candidate) {
        return candidate.equals(world) || candidate.getName().equals(world.getName() + "_demo_destination");
    }

    private void link(LocalPortal source, LocalPortal destination) {
        if (!source.setDestination(destination) || !destination.setDestination(source)) {
            throw new IllegalStateException("Could not link " + source.getName() + " and " + destination.getName());
        }
    }

    private void clearNetwork() {
        NexusSubsystem nexus = NexusSubsystem.active();
        if (nexus == null || nexus.registry() == null) {
            return;
        }
        try {
            nexus.registry().delete(NETWORK_ID);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not clear GardenRoutes", exception);
        }
    }

    private void linkNetwork(LocalPortal source, LocalPortal destination, LocalPortal distant) {
        NexusSubsystem nexus = Objects.requireNonNull(NexusSubsystem.active(), "Nexus is unavailable");
        NetworkRegistry registry = Objects.requireNonNull(nexus.registry(), "Nexus registry is unavailable");
        PortalNetwork network = PortalNetwork.create(NETWORK_ID, "GardenRoutes", null).withVisibility(Visibility.PUBLIC);
        LocalPortal[] portals = new LocalPortal[]{source, destination, distant};
        String[] addresses = new String[]{"GARDEN", "SUN", "AMBER"};
        for (int index = 0; index < portals.length; index++) {
            LocalPortal portal = portals[index];
            network = network.withMember(portal.getId(), new NetworkMember(portal.getId(), addresses[index], portal.getName(), System.currentTimeMillis(), null));
        }
        try {
            registry.save(network);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not save GardenRoutes", exception);
        }
        for (int index = 0; index < portals.length; index++) {
            LocalPortal portal = portals[index];
            NexusPortalExtension state = Objects.requireNonNull(portal.extension(NexusPortalExtension.class), "Nexus portal extension is unavailable");
            state.setNetworkId(NETWORK_ID);
            state.setAddress(addresses[index]);
            state.setLabel(portal.getName());
            portal.save();
        }
        block(-4, 70, 3, Material.DEEPSLATE_TILES);
        Switch lever = (Switch) Material.LEVER.createBlockData();
        lever.setAttachedFace(FaceAttachable.AttachedFace.FLOOR);
        lever.setFacing(BlockFace.NORTH);
        lever.setPowered(false);
        world.getBlockAt(-4, 71, 3).setBlockData(lever, false);
        NexusPortalExtension state = source.extension(NexusPortalExtension.class);
        state.setFrameIo(new FrameIo(-4, 0, 3, FrameIo.RedstoneAction.NONE, FrameIo.ComparatorOutput.NONE));
        source.save();
    }

    private void report(CommandSender sender) {
        DemoScene scene = new DemoScene(world);
        List<ILocalPortal> portals = Wormholes.portalManager.getLocalPortals();
        int count = 0;
        World destinationWorld = world;
        boolean destinationSideFrame = false;
        for (ILocalPortal portal : portals) {
            if (fixtureWorld(portal.getWorld())) {
                count++;
                if (portal.getName().equals("Sun Court")) {
                    destinationWorld = portal.getWorld();
                    destinationSideFrame = portal.getFrame().getNormal() == Direction.E || portal.getFrame().getNormal() == Direction.W;
                }
            }
        }
        FeatureScenes destination = new FeatureScenes(destinationWorld);
        boolean destinationFrame = destinationSideFrame ? destination.sideFrame() : new DemoScene(destinationWorld).hasFrame(200);
        sender.sendMessage("featurePortals=" + count + ", sourceFrame=" + scene.hasFrame(0)
                + ", destinationFrame=" + destinationFrame + ", sourceMarkers=" + markers(0) + ", destinationMarkers=" + destination.markers(200));
        for (ILocalPortal portal : portals) {
            if (!fixtureWorld(portal.getWorld())) {
                continue;
            }
            ITunnel tunnel = portal.getTunnel();
            PortalFrame frame = portal.getFrame();
            sender.sendMessage("portal=" + portal.getId() + ", name=" + portal.getName() + ", z=" + portal.getCenter().getBlockZ()
                    + ", world=" + portal.getWorld().getName()
                    + ", worldId=" + portal.getWorld().getUID()
                    + ", cells=" + portal.getStructure().getBlockPositions().size() + ", type=" + portal.getType()
                    + ", open=" + portal.isOpen() + ", linked=" + (tunnel != null && tunnel.isValid())
                    + ", destination=" + (tunnel == null ? "none" : tunnel.getDestinationId())
                    + ", remoteServer=" + (tunnel instanceof UniversalTunnel remote ? remote.getServerName() : "none")
                    + ", normal=" + frame.getNormal() + ", right=" + frame.getRight() + ", up=" + frame.getUp()
                    + ", mirror=" + portal.isMirrorMode() + ", mirrorRotation=" + portal.getMirrorRotation()
                    + ", projection=" + portal.getProjectionMode() + ", render=" + portal.getRenderMode()
                    + ", viewDepth=" + portal.getNetworkViewDepth() + ", lateralPad=" + portal.getNetworkViewLateralPad()
                    + ", ambient=" + portal.getAmbientStyle() + ", ambientColor=" + portal.getAmbientColor()
                    + ", blackout=" + portal.isBlackoutBackground() + ", blackoutColor=" + portal.getBlackoutColor()
                    + ", skin=" + portal.getSurfaceSkin() + ", outgoing=" + portal.isOutgoingTraversalsEnabled()
                    + ", incoming=" + portal.isIncomingTraversalsEnabled());
            if (portal instanceof LocalPortal local) {
                reportExtensions(sender, local);
            }
        }
        for (Entity entity : world.getEntities()) {
            if (entity.getScoreboardTags().contains(LIVE_ENTITY_TAG)) {
                Location location = entity.getLocation();
                sender.sendMessage("liveEntity=" + entity.getUniqueId() + ", entityType=" + entity.getType()
                        + ", x=" + location.getX() + ", y=" + location.getY() + ", z=" + location.getZ());
            }
        }
    }

    private void reportExtensions(CommandSender sender, LocalPortal portal) {
        FidelityPortalExtension fidelity = portal.extension(FidelityPortalExtension.class);
        if (fidelity != null) {
            sender.sendMessage("fidelityPortal=" + portal.getId() + ", atmosphere=" + fidelity.effectiveAtmosphereMode()
                    + ", acoustics=" + fidelity.effectiveAcousticsProfile() + ", blockEntities=" + fidelity.effectiveBlockEntities()
                    + ", biome=" + portal.getWorld().getBiome(portal.getCenter()) + ", time=" + portal.getWorld().getTime()
                    + ", storm=" + portal.getWorld().hasStorm());
        }
        TransitPortalExtension transit = portal.extension(TransitPortalExtension.class);
        if (transit != null) {
            sender.sendMessage("transitPortal=" + portal.getId() + ", momentum=" + transit.momentum()
                    + ", orientation=" + transit.orientation() + ", membrane=" + transit.isMembrane()
                    + ", bounce=" + transit.isBounce() + ", profile=" + transit.profile().encode());
        }
        NexusPortalExtension nexus = portal.extension(NexusPortalExtension.class);
        if (nexus != null) {
            sender.sendMessage("nexusPortal=" + portal.getId() + ", network=" + nexus.networkId()
                    + ", address=" + nexus.address() + ", dial=" + nexus.dial().currentAddress()
                    + ", redstone=" + nexus.frameIo().action() + ", reciprocal=" + nexus.reciprocal());
        }
        RtpSettings rtp = portal.getRtpSettings();
        if (rtp != null) {
            sender.sendMessage("rtpPortal=" + portal.getId() + ", center=" + rtp.getCenterMode()
                    + ", centerX=" + rtp.getCustomCenterX() + ", centerZ=" + rtp.getCustomCenterZ()
                    + ", radius=" + rtp.getMinimumRadius() + ".." + rtp.getMaximumRadius()
                    + ", allocation=" + rtp.getAllocationMode() + ", rotation=" + rtp.getRotationMode()
                    + ", safety=" + rtp.getSafetyMode());
            reportRtpRuntime(sender, portal);
        }
    }

    private void reportRtpRuntime(CommandSender sender, LocalPortal portal) {
        RtpService.Snapshot snapshot = Wormholes.rtpRuntime == null ? null : Wormholes.rtpRuntime.snapshotOrNull(portal.getId());
        if (snapshot == null) {
            sender.sendMessage("rtpRuntimePortal=" + portal.getId() + ", ready=false, active=none");
            return;
        }
        sender.sendMessage("rtpRuntimePortal=" + portal.getId() + ", ready=" + snapshot.runtime().ready()
                + ", active=" + coordinates(snapshot.runtime().active()) + ", standby=" + coordinates(snapshot.runtime().standby())
                + ", routeRevision=" + snapshot.runtime().routeRevision());
        for (Map.Entry<UUID, RtpPortalRuntime.PlayerDestination> entry : snapshot.playerDestinations().entrySet()) {
            sender.sendMessage("rtpPlayer=" + entry.getKey() + ", portal=" + portal.getId() + ", destination=" + coordinates(entry.getValue().destination()));
        }
    }

    private String coordinates(RtpDestination destination) {
        return destination == null ? "none" : destination.worldKey() + ":" + destination.blockX() + "," + destination.feetY() + "," + destination.blockZ();
    }

    private boolean sideFrame() {
        for (int z = 198; z <= 202; z++) {
            if (world.getBlockAt(0, 69, z).isEmpty() || world.getBlockAt(0, 73, z).isEmpty()) {
                return false;
            }
        }
        for (int y = 70; y <= 72; y++) {
            if (world.getBlockAt(0, y, 198).isEmpty() || world.getBlockAt(0, y, 202).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private int markers(int offset) {
        int count = 0;
        for (int x = -1; x <= 1; x++) {
            for (int y = 70; y <= 72; y++) {
                if (world.getBlockAt(x, y, offset).getType() == Material.GLASS) {
                    count++;
                }
            }
        }
        return count;
    }

    private void clearAperture(int offset) {
        for (int x = -1; x <= 1; x++) {
            for (int y = 70; y <= 72; y++) {
                block(x, y, offset, Material.AIR);
            }
        }
    }

    private void hills() {
        for (int x = -12; x <= 12; x++) {
            for (int z = -24; z <= -5; z++) {
                double front = ridge(x, z, 0, -8, 10, 4, 10);
                double rear = ridge(x, z, -1, -19, 7, 5, 6);
                double shoulder = ridge(x, z, 7, -14, 5, 6, 7);
                int top = 69 + (int) Math.floor(Math.max(front, Math.max(rear, shoulder)));
                block(x, 69, 200 + z, Material.GRASS_BLOCK);
                for (int y = 70; y <= 86; y++) {
                    Material material = y > top ? Material.AIR : y == top ? Material.GRASS_BLOCK
                            : y >= top - 2 ? Material.DIRT : Material.STONE;
                    block(x, y, 200 + z, material);
                }
            }
        }
        for (int x = -6; x <= -4; x++) {
            for (int y = 70; y <= 71; y++) {
                for (int z = 187; z <= 194; z++) {
                    block(x, y, z, Material.AIR);
                }
            }
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = 180; z <= 182; z++) {
                block(x, 75, z, Material.RED_TERRACOTTA);
                block(x, 76, z, Material.RED_TERRACOTTA);
            }
        }
        block(0, 77, 181, Material.RED_TERRACOTTA);
        block(0, 78, 181, Material.GOLD_BLOCK);
    }

    private double ridge(int x, int z, int centerX, int centerZ, int radiusX, int radiusZ, int height) {
        double dx = (x - centerX) / (double) radiusX;
        double dz = (z - centerZ) / (double) radiusZ;
        double envelope = Math.max(0.0, 1.0 - Math.sqrt(dx * dx + dz * dz));
        return envelope * (height + 0.7 * Math.sin((x - centerX) * 0.8) * Math.sin((z - centerZ) * 0.6));
    }

    private void landmarks(int offset) {
        tower(-7, offset - 8, Material.RED_TERRACOTTA, 5);
        tower(7, offset - 11, Material.PRISMARINE_BRICKS, 3);
        tower(-8, offset + 12, Material.RED_TERRACOTTA, 3);
        tower(7, offset + 7, Material.PRISMARINE_BRICKS, 5);
    }

    private void biome(int offset, Biome biome) {
        for (int x = -32; x <= 32; x += 4) {
            for (int z = -32; z <= 32; z += 4) {
                for (int y = 64; y <= 96; y += 4) {
                    world.setBiome(x, y, offset + z, biome);
                }
            }
        }
    }

    private void wetland() {
        for (int x = -16; x <= 16; x++) {
            for (int z = 180; z <= 195; z++) {
                if (Math.abs(x) >= 4) {
                    block(x, 69, z, Material.GRASS_BLOCK);
                }
            }
        }
        for (int x = -10; x <= -5; x++) {
            for (int z = 187; z <= 192; z++) {
                block(x, 68, z, Material.CLAY);
                block(x, 69, z, Material.WATER);
            }
        }
        block(-7, 70, 189, Material.LILY_PAD);
        block(-8, 70, 191, Material.LILY_PAD);
        for (int x = -3; x <= 3; x++) {
            for (int z = 190; z <= 195; z++) {
                block(x, 69, z, Material.GRASS_BLOCK);
            }
        }
        for (int z = 191; z <= 194; z++) {
            block(0, 68, z, Material.CLAY);
            block(0, 69, z, Material.WATER);
        }
        Leaves leaves = (Leaves) Material.OAK_LEAVES.createBlockData();
        leaves.setPersistent(true);
        for (int x : new int[]{-2, 2}) {
            for (int y = 70; y <= 72; y++) {
                world.getBlockAt(x, y, 192).setBlockData(leaves, false);
            }
        }
        block(0, 70, 193, Material.LILY_PAD);
    }

    private void liveScene() {
        for (int x = 2; x <= 6; x++) {
            for (int z = 190; z <= 195; z++) {
                block(x, 69, z, Material.GRASS_BLOCK);
                block(x, 70, z, x == 2 || x == 6 || z == 190 || z == 195 ? Material.OAK_FENCE : Material.AIR);
            }
        }
        Sheep sheep = world.spawn(new Location(world, 4.5, 70.0, 192.5), Sheep.class);
        sheep.setColor(DyeColor.ORANGE);
        sheep.setPersistent(true);
        sheep.setRemoveWhenFarAway(false);
        sheep.setInvulnerable(true);
        sheep.addScoreboardTag(LIVE_ENTITY_TAG);
        for (int x = -6; x <= -4; x++) {
            for (int z = 188; z <= 193; z++) {
                block(x, 68, z, Material.PRISMARINE);
                block(x, 69, z, x == -5 && z > 188 && z < 193 ? Material.AIR : Material.SMOOTH_SANDSTONE);
            }
        }
        for (int y = 70; y <= 73; y++) {
            block(-5, y, 188, Material.CUT_SANDSTONE);
        }
        world.getBlockAt(-5, 73, 189).setType(Material.WATER, true);
    }

    private void tower(int x, int z, Material material, int height) {
        for (int y = 70; y < 70 + height; y++) {
            block(x, y, z, material);
        }
        block(x, 70 + height, z, Material.LANTERN);
    }

    private void terrace() {
        for (int x = -20; x <= 20; x++) {
            for (int z = 380; z <= 420; z++) {
                for (int y = 70; y <= 90; y++) {
                    block(x, y, z, Material.AIR);
                }
                block(x, 68, z, Material.STONE);
                block(x, 69, z, Math.abs(x) < 3 ? Material.SMOOTH_RED_SANDSTONE : Material.RED_SANDSTONE);
            }
        }
        frame(400, Material.CUT_RED_SANDSTONE);
        landmarks(400);
        for (int x : new int[]{-12, 12}) {
            for (int z : new int[]{387, 413}) {
                tower(x, z, Material.CHISELED_RED_SANDSTONE, 7);
            }
        }
    }

    private void frame(int offset, Material material) {
        for (int x = -2; x <= 2; x++) {
            block(x, 69, offset, material);
            block(x, 73, offset, material);
        }
        for (int y = 70; y <= 72; y++) {
            block(-2, y, offset, material);
            block(2, y, offset, material);
        }
        clearAperture(offset);
    }

    private void block(int x, int y, int z, Material material) {
        world.getBlockAt(x, y, z).setType(material, false);
    }
}
