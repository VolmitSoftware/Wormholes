package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.Aperture;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.SizeRatio;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.PortalIrisMainPipelines;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.MinecraftScaleAccess;
import art.arcane.wormholes.modded.clientview.MinecraftPortalEnvironment;
import art.arcane.wormholes.modded.seamless.StraddleTracker;
import art.arcane.wormholes.network.client.TravelMessage;
import art.arcane.wormholes.transit.TravellerScale;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class ClientSeamlessTravel {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");
    private static final boolean IRIS = ClientSeamlessTravel.class.getClassLoader().getResource("net/irisshaders/iris/Iris.class") != null;
    private static final long ACCEPT_TIMEOUT_MILLIS = 2_000L;
    private static final int MAX_CROSSINGS_PER_FRAME = 3;
    private static final double CHECKPOINT_NUDGE = 0.001D;
    private static final double POSITION_TOLERANCE = 1.0E-3D;
    private static final float LOOK_TOLERANCE = 0.5F;
    private static final long CROSS_REVISION = 1L;
    private static final float TICK_END_PARTIAL = 0.0F;
    private static final TravellerScale<Entity> TRAVELLER_SCALE = new TravellerScale<>(MinecraftScaleAccess.scaleAttribute());

    private final Consumer<TravelMessage> sender;
    private final ResidentLevels residents;
    private final Map<UUID, TravelMessage.TravelBegin> arms = new LinkedHashMap<>();
    private final ArrayDeque<Crossing> pending = new ArrayDeque<>();
    private final ClientEntityCrossings entities = new ClientEntityCrossings();
    private final ClientCameraRoll cameraRoll = new ClientCameraRoll();
    private final ClientCrossingView view;
    private ClientLevel warmedSource;
    private EnvironmentState warmedEnvironment;
    private Vec3 previousEye;
    private UUID declined;
    private StraddleTracker.Straddle returning;
    private boolean straddling;

    public ClientSeamlessTravel(Consumer<TravelMessage> sender, ResidentLevels residents) {
        this.sender = sender;
        this.residents = residents;
        this.view = new ClientCrossingView(residents);
    }

    public ResidentLevels residents() {
        return residents;
    }

    public ClientCrossingView view() {
        return view;
    }

    public void deliver(TravelMessage message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> deliver(message));
            return;
        }
        try {
            receive(message);
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to apply portal travel message {}", message.getClass().getSimpleName(), failure);
        }
    }

    public boolean receive(TravelMessage message) {
        return switch (message) {
            case TravelMessage.RemoteLevelOpen open -> {
                residents.open(open);
                yield true;
            }
            case TravelMessage.RemoteLevelClose close -> {
                residents.close(close);
                yield true;
            }
            case TravelMessage.RoutedPacket packet -> {
                residents.route(packet);
                yield true;
            }
            case TravelMessage.TravelBegin begin -> {
                arms.put(begin.sourcePortal(), begin);
                yield true;
            }
            case TravelMessage.TravelAccept accept -> {
                accept(accept);
                yield true;
            }
            case TravelMessage.TravelCancel cancel -> cancel(cancel);
            case TravelMessage.EntityCrossed crossed -> {
                Minecraft minecraft = Minecraft.getInstance();
                entities.receive(crossed.levelHandle() == 0 ? minecraft.level : residents.level(crossed.levelHandle()), minecraft.player, crossed);
                yield true;
            }
            default -> false;
        };
    }

    public boolean armed() {
        return !arms.isEmpty();
    }

    public Collection<TravelMessage.TravelBegin> arms() {
        return Collections.unmodifiableCollection(arms.values());
    }

    public ClientCameraRoll cameraRoll() {
        return cameraRoll;
    }

    public boolean pending() {
        return !pending.isEmpty();
    }

    public boolean armed(UUID source) {
        return arms.containsKey(source);
    }

    public void frame(Camera camera, DeltaTracker tracker) {
        if (beforeFrame(camera, tracker)) {
            camera.update(tracker);
        }
        view.update(camera, tracker, arms.values());
        cameraRoll.apply(camera, System.currentTimeMillis());
    }

    public boolean beforeFrame(Camera camera, DeltaTracker tracker) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.level == null || player == null || !camera.isInitialized() || camera.entity() != player) {
            previousEye = null;
            return false;
        }
        return detect(player, camera.getCameraEntityPartialTicks(tracker));
    }

    public void afterTick() {
        entities.tick();
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.level == null || player == null || minecraft.getCameraEntity() != player) {
            return;
        }
        detect(player, TICK_END_PARTIAL);
    }

    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.getConnection() == null) {
            return;
        }
        Crossing oldest = pending.peekFirst();
        if (oldest != null && System.currentTimeMillis() >= oldest.deadline()) {
            rollback(oldest, "no server answer within " + ACCEPT_TIMEOUT_MILLIS + " ms");
        }
        if (arms.isEmpty() && returning == null) {
            if (straddling) {
                straddling = false;
                StraddleTracker.clear(player);
            }
            retireReturnView();
            return;
        }
        straddle(minecraft, player);
        warm(minecraft, player);
    }

    public void serverPosition() {
        Crossing oldest = pending.peekFirst();
        if (oldest != null) {
            rollback(oldest, "server position correction");
        }
        previousEye = null;
    }

    public void clear() {
        arms.clear();
        pending.clear();
        entities.clear();
        retireReturnView();
        view.clear();
        residents.clear();
        previousEye = null;
        declined = null;
        returning = null;
        straddling = false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            StraddleTracker.clear(player);
        }
    }

    static boolean crossed(ApertureDescriptor geometry, Vec3 previous, Vec3 current) {
        double side = geometry.frontSide() ? 1 : -1;
        double before = geometry.signedDistance(previous.x, previous.y, previous.z) * side;
        double after = geometry.signedDistance(current.x, current.y, current.z) * side;
        if (before <= 0 || after > 0) {
            return false;
        }
        Vec3 intersection = previous.lerp(current, before / (before - after));
        return geometry.containsPoint(intersection.x, intersection.y, intersection.z);
    }

    static TravelMessage.TravelPose crossingPose(LocalPlayer player, float partial) {
        Vec3 feet = player.getPosition(partial);
        return new TravelMessage.TravelPose(feet.x, feet.y, feet.z, player.getYRot(), player.getXRot());
    }

    static StraddleTracker.Straddle straddle(TravelMessage.TravelBegin value, Level destination, Box stretched, Vec3d eye) {
        ApertureCells aperture = value.sourceGeometry().aperture();
        if (!StraddleTracker.qualifies(stretched, aperture)) {
            return null;
        }
        return StraddleTracker.create(sourceEndpoint(value, aperture), destinationEndpoint(value), destination, eye, value.scale());
    }

    static StraddleTracker.Endpoint sourceEndpoint(TravelMessage.TravelBegin value, Aperture aperture) {
        ApertureDescriptor geometry = value.sourceGeometry();
        return new StraddleTracker.Endpoint(aperture, geometry.frame(), planePoint(geometry));
    }

    static StraddleTracker.Endpoint destinationEndpoint(TravelMessage.TravelBegin value) {
        ApertureDescriptor geometry = value.sourceGeometry();
        Similarity toward = value.sourceToDestination();
        ApertureCells aperture = new ApertureCells();
        aperture.setArea(toward.box(geometry.apertureArea()));
        Frame exit = ClientTravelMotion.exitFrame(geometry.frame().view(geometry.frontSide()), toward.rigid(), geometry.frontSide());
        return new StraddleTracker.Endpoint(aperture, exit, toward.point(planePoint(geometry)));
    }

    private boolean detect(LocalPlayer player, float partial) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 eye = player.getEyePosition(partial);
        Vec3 previous = previousEye;
        previousEye = eye;
        if (previous == null || arms.isEmpty() || minecraft.getConnection() == null || player.isPassenger() || player.isDeadOrDying()) {
            return false;
        }
        boolean crossedAny = false;
        for (int combo = 0; combo < MAX_CROSSINGS_PER_FRAME; combo++) {
            TravelMessage.TravelBegin arm = crossedArm(minecraft.level, previous, eye);
            if (arm == null) {
                break;
            }
            Vec3 checkpoint = cross(minecraft, player, arm, partial, previous, eye);
            if (checkpoint == null) {
                break;
            }
            crossedAny = true;
            previous = checkpoint;
            eye = player.getEyePosition(partial);
        }
        previousEye = eye;
        return crossedAny;
    }

    private TravelMessage.TravelBegin crossedArm(ClientLevel level, Vec3 previous, Vec3 eye) {
        String dimension = level.dimension().identifier().toString();
        TravelMessage.TravelBegin nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (TravelMessage.TravelBegin arm : arms.values()) {
            ApertureDescriptor geometry = arm.sourceGeometry();
            if (!arm.sourceWorld().equals(dimension) || !crossed(geometry, previous, eye)) {
                continue;
            }
            double distance = Math.abs(geometry.signedDistance(previous.x, previous.y, previous.z));
            if (distance < nearestDistance) {
                nearest = arm;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private Vec3 cross(Minecraft minecraft, LocalPlayer player, TravelMessage.TravelBegin arm, float partial, Vec3 previous, Vec3 eye) {
        ClientLevel source = minecraft.level;
        ClientLevel target = arm.resident() ? residents.level(arm.levelHandle()) : source;
        Pose before = ClientTravelMotion.capture(player);
        TravelMessage.TravelPose crossingPose = crossingPose(player, partial);
        Vec3 feet = new Vec3(crossingPose.x(), crossingPose.y(), crossingPose.z());
        Vec3d crossingFeet = new Vec3d(feet.x, feet.y, feet.z);
        Pose after = ClientTravelMotion.arrive(arm, before, crossingFeet);
        String refusal = target == null ? "resident level " + arm.levelHandle() + " is not open"
            : arrivalLoaded(target, after.position()) ? null : "arrival terrain not received";
        if (refusal != null) {
            if (!arm.token().equals(declined)) {
                declined = arm.token();
                LOGGER.info("Crossing declined {} -> {}: {}", arm.sourceWorld(), arm.world().dimension(), refusal);
            }
            return null;
        }
        Similarity toward = arm.sourceToDestination();
        Vec3 expected = ClientTravelMotion.point(toward, feet);
        String preparing = target == source ? null : preparing(target);
        ClientTravelMotion.Carry carry = ClientTravelMotion.carry(player);
        double scaleBefore = TRAVELLER_SCALE.factor(player);
        try {
            sender.accept(new TravelMessage.TravelCross(arm.token(), arm.generation(), CROSS_REVISION, crossingPose,
                ClientTravelMotion.vector(previous), ClientTravelMotion.vector(eye)));
            ScaleRule rule = arm.rules().scale();
            if (rule.changesEntity()) {
                TRAVELLER_SCALE.cross(player, rule, new SizeRatio(arm.scale(), true));
            }
            ClientTravelMotion.Carry carried = carry.moved(before, after, toward);
            if (target != source) {
                ClientLevelSwitch.activate(residents, target, after, carried);
            } else {
                ClientTravelMotion.apply(player, after);
                carried.restore(player);
            }
            ClientWorldLoader.forceFullSectionDiscovery();
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to predict seamless portal crossing", failure);
            TRAVELLER_SCALE.set(player, scaleBefore);
            return null;
        }
        long now = System.currentTimeMillis();
        cameraRoll.start(ClientTravelMotion.roll(arm, before, crossingFeet), rollSeconds(), now);
        pending.addLast(new Crossing(arm, source, target, before, carry, after, expected, now + ACCEPT_TIMEOUT_MILLIS, preparing, scaleBefore));
        residents.crossing(pending.peekFirst().source() == pending.peekFirst().target() ? null : pending.peekFirst().source());
        declined = null;
        returning = StraddleTracker.create(destinationEndpoint(arm), sourceEndpoint(arm, arm.sourceGeometry().aperture()), source,
            ClientTravelMotion.vector(player.getEyePosition()), 1.0D / arm.scale());
        StraddleTracker.register(player, returning);
        straddling = true;
        return checkpoint(arm, toward, previous, eye);
    }

    private void accept(TravelMessage.TravelAccept accept) {
        Crossing first = pending.peekFirst();
        if (first != null && first.arm().token().equals(accept.token()) && first.arm().generation() == accept.generation()) {
            pending.removeFirst();
            confirmed(first, accept);
            return;
        }
        if (first != null) {
            rollback(first, "the server crossed through another portal");
        }
        serverCrossing(accept);
    }

    private void confirmed(Crossing crossing, TravelMessage.TravelAccept accept) {
        TravelMessage.TravelBegin arm = crossing.arm();
        LOGGER.info("Crossing seamless {} -> {}{}{}", arm.sourceWorld(), arm.world().dimension(),
            accept.dimensionChanged() ? " (resident " + accept.levelHandle() + ")" : "",
            crossing.preparing() == null ? "" : ", still preparing " + crossing.preparing());
        Crossing next = pending.peekFirst();
        residents.crossing(next == null || next.source() == next.target() ? null : next.source());
        if (crossing.source() != crossing.target()) {
            residents.retire(crossing.source());
        }
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.dropProjectedEntities(arm.sourceGeometry());
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || next != null) {
            return;
        }
        TravelMessage.TravelPose authoritative = accept.pose();
        Vec3d offset = new Vec3d(authoritative.x() - crossing.expected().x, authoritative.y() - crossing.expected().y,
            authoritative.z() - crossing.expected().z);
        Pose current = ClientTravelMotion.capture(player);
        double tolerance = POSITION_TOLERANCE * Math.max(1.0D, arm.scale());
        if (offset.lengthSquared() <= tolerance * tolerance && lookMatches(authoritative, crossing.after())) {
            ClientTravelMotion.apply(player, current.moved(offset));
            return;
        }
        LOGGER.warn("Seamless crossing corrected by the server: offset {} look {} {} predicted {} {}", offset, authoritative.yaw(),
            authoritative.pitch(), crossing.after().yaw(), crossing.after().pitch());
        ClientTravelMotion.apply(player, ClientTravelMotion.turned(ClientTravelMotion.reconcile(current, offset, crossing.after().velocity(),
            accept.velocity()), Angles.unwrap(authoritative.yaw() - crossing.after().yaw(), 0.0F), authoritative.pitch() - crossing.after().pitch()));
        ClientWorldLoader.forceFullSectionDiscovery();
    }

    private static boolean lookMatches(TravelMessage.TravelPose authoritative, Pose predicted) {
        return Math.abs(Angles.unwrap(authoritative.yaw() - predicted.yaw(), 0.0F)) <= LOOK_TOLERANCE
            && Math.abs(authoritative.pitch() - predicted.pitch()) <= LOOK_TOLERANCE;
    }

    private void serverCrossing(TravelMessage.TravelAccept accept) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        TravelMessage.TravelBegin arm = arm(accept.token(), accept.generation());
        if (player == null || minecraft.level == null) {
            return;
        }
        ClientLevel source = minecraft.level;
        ClientLevel target = accept.dimensionChanged() ? residents.level(accept.levelHandle()) : source;
        if (target == null) {
            LOGGER.warn("Crossing seamless by the server could not swap: resident level {} is not open", accept.levelHandle());
            sender.accept(new TravelMessage.RemoteLevelReopen(accept.levelHandle()));
            return;
        }
        Pose before = ClientTravelMotion.capture(player);
        Pose mapped = arm == null ? before : ClientTravelMotion.arrive(arm, before, before.position());
        TravelMessage.TravelPose pose = accept.pose();
        Vec3d offset = new Vec3d(pose.x() - mapped.position().x(), pose.y() - mapped.position().y(), pose.z() - mapped.position().z());
        Pose moved = mapped.moved(offset).withVelocity(accept.velocity());
        Pose placed = lookMatches(pose, moved) ? moved
            : ClientTravelMotion.turned(moved, Angles.unwrap(pose.yaw() - moved.yaw(), 0.0F), pose.pitch() - moved.pitch());
        ClientTravelMotion.Carry carried = arm == null ? ClientTravelMotion.carry(player)
            : ClientTravelMotion.carry(player).moved(before, placed, arm.sourceToDestination());
        if (arm != null) {
            cameraRoll.start(ClientTravelMotion.roll(arm, before, before.position()), rollSeconds(), System.currentTimeMillis());
        }
        if (target != source) {
            ClientLevelSwitch.activate(residents, target, placed, carried);
            residents.retire(source);
        } else {
            ClientTravelMotion.apply(player, placed);
            carried.restore(player);
        }
        ClientWorldLoader.forceFullSectionDiscovery();
        previousEye = null;
        LOGGER.info("Crossing seamless {} -> {} by the server{}", arm == null ? source.dimension().identifier() : arm.sourceWorld(),
            target.dimension().identifier(), accept.dimensionChanged() ? " (resident " + accept.levelHandle() + ")" : "");
        WormholesClient client = WormholesClient.instance();
        if (client != null && arm != null) {
            client.dropProjectedEntities(arm.sourceGeometry());
        }
    }

    private boolean cancel(TravelMessage.TravelCancel cancel) {
        for (Crossing crossing : pending) {
            if (crossing.arm().token().equals(cancel.token()) && crossing.arm().generation() == cancel.generation()) {
                rollback(crossing, "server rejected the crossing");
                return true;
            }
        }
        Iterator<Map.Entry<UUID, TravelMessage.TravelBegin>> iterator = arms.entrySet().iterator();
        while (iterator.hasNext()) {
            TravelMessage.TravelBegin arm = iterator.next().getValue();
            if (arm.token().equals(cancel.token()) && arm.generation() == cancel.generation()) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private void rollback(Crossing from, String reason) {
        LOGGER.info("Crossing rolled back {} -> {}: {}", from.arm().sourceWorld(), from.arm().world().dimension(), reason);
        while (!pending.isEmpty() && pending.peekLast() != from) {
            pending.removeLast();
        }
        pending.pollLast();
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Crossing next = pending.peekFirst();
        residents.crossing(next == null || next.source() == next.target() ? null : next.source());
        previousEye = null;
        returning = null;
        if (player == null) {
            return;
        }
        StraddleTracker.clear(player);
        cameraRoll.cancel();
        if (TRAVELLER_SCALE.factor(player) != from.scaleBefore()) {
            TRAVELLER_SCALE.set(player, from.scaleBefore());
        }
        if (minecraft.level != from.source()) {
            ClientLevelSwitch.activate(residents, from.source(), from.before(), from.carry());
        } else {
            ClientTravelMotion.apply(player, from.before());
            from.carry().restore(player);
        }
        ClientWorldLoader.forceFullSectionDiscovery();
    }

    private void straddle(Minecraft minecraft, LocalPlayer player) {
        Box stretched = StraddleTracker.stretched(box(player.getBoundingBox()), ClientTravelMotion.vector(player.getDeltaMovement()),
            new Vec3d(player.xo - player.getX(), player.yo - player.getY(), player.zo - player.getZ()));
        Vec3d eye = ClientTravelMotion.vector(player.getEyePosition());
        String dimension = minecraft.level.dimension().identifier().toString();
        for (TravelMessage.TravelBegin arm : arms.values()) {
            ClientLevel target = arm.resident() ? residents.level(arm.levelHandle()) : minecraft.level;
            StraddleTracker.Straddle straddle = target == null || !arm.sourceWorld().equals(dimension) ? null : straddle(arm, target, stretched, eye);
            if (straddle != null) {
                StraddleTracker.register(player, straddle);
                straddling = true;
                returning = null;
                return;
            }
        }
        if (returning != null && StraddleTracker.qualifies(stretched, returning.aperture())) {
            StraddleTracker.register(player, returning);
            straddling = true;
            return;
        }
        returning = null;
        straddling = false;
        StraddleTracker.clear(player);
    }

    public String unprepared() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        TravelMessage.TravelBegin nearest = player == null || minecraft.level == null ? null : nearestResident(minecraft.level, player);
        if (nearest == null) {
            return "no armed destination";
        }
        ClientLevel level = residents.level(nearest.levelHandle());
        return level == null ? "destination level" : preparing(level);
    }

    private static String preparing(ClientLevel level) {
        if (IRIS && !PortalIrisMainPipelines.ready(level)) {
            return "destination shaders";
        }
        if (IRIS && !ClientPortalRenderer.instance().travelSourceShaderReady()) {
            return "return view shaders";
        }
        return null;
    }

    private TravelMessage.TravelBegin nearestResident(ClientLevel current, LocalPlayer player) {
        TravelMessage.TravelBegin nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        String dimension = current.dimension().identifier().toString();
        for (TravelMessage.TravelBegin arm : arms.values()) {
            if (!arm.resident() || !arm.sourceWorld().equals(dimension)) {
                continue;
            }
            double distance = Math.abs(arm.sourceGeometry().signedDistance(player.getX(), player.getEyeY(), player.getZ()));
            if (distance < nearestDistance) {
                nearest = arm;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void warm(Minecraft minecraft, LocalPlayer player) {
        if (!IRIS) {
            return;
        }
        TravelMessage.TravelBegin nearest = nearestResident(minecraft.level, player);
        ClientLevel level = nearest == null ? null : residents.level(nearest.levelHandle());
        if (level == null) {
            return;
        }
        try {
            warmReturnView(minecraft.level, player);
            PortalIrisMainPipelines.prepare(level);
        } catch (RuntimeException failure) {
            LOGGER.warn("Unable to prepare the {} shaders behind portal {}", level.dimension().identifier(), nearest.sourcePortal(), failure);
        }
    }

    private void warmReturnView(ClientLevel level, LocalPlayer player) {
        if (warmedSource != level) {
            Vec3 eye = player.getEyePosition();
            warmedEnvironment = MinecraftPortalEnvironment.capture(level, new Vec3d(eye.x, eye.y, eye.z), OpticTransform.IDENTITY,
                ((ClientTravelWorld) level).wormholes$travelWorld().flat());
            warmedSource = level;
        }
        ClientPortalRenderer.instance().prepareTravelSourceEnvironment(warmedEnvironment);
    }

    private void retireReturnView() {
        if (warmedSource != null) {
            warmedSource = null;
            warmedEnvironment = null;
            ClientPortalRenderer.instance().retireTravelSource();
        }
    }

    private TravelMessage.TravelBegin arm(UUID token, long generation) {
        for (TravelMessage.TravelBegin arm : arms.values()) {
            if (arm.token().equals(token) && arm.generation() == generation) {
                return arm;
            }
        }
        return null;
    }

    private static boolean arrivalLoaded(ClientLevel level, Vec3d feet) {
        int x = (int) Math.floor(feet.x()) >> 4;
        int z = (int) Math.floor(feet.z()) >> 4;
        return level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null;
    }

    private static double rollSeconds() {
        WormholesClient client = WormholesClient.instance();
        WormholesClientConfig config = client == null ? null : client.config();
        return config == null ? WormholesClientConfig.DEFAULT_CAMERA_ROLL_EASE_SECONDS : config.cameraRollEaseSeconds;
    }

    private static Vec3 checkpoint(TravelMessage.TravelBegin value, Similarity toward, Vec3 previous, Vec3 eye) {
        ApertureDescriptor geometry = value.sourceGeometry();
        double before = geometry.signedDistance(previous.x, previous.y, previous.z);
        double after = geometry.signedDistance(eye.x, eye.y, eye.z);
        Vec3 crossing = before == after ? eye : previous.lerp(eye, before / (before - after));
        Vec3 motion = ClientTravelMotion.position(toward.vector(ClientTravelMotion.vector(eye.subtract(previous))));
        Vec3 mapped = ClientTravelMotion.point(toward, crossing);
        return motion.lengthSqr() == 0.0D ? mapped : mapped.add(motion.normalize().scale(CHECKPOINT_NUDGE));
    }

    private static Vec3d planePoint(ApertureDescriptor geometry) {
        Vec3d center = geometry.apertureArea().center();
        double plane = geometry.planeCoordinate();
        return switch (geometry.facingDirection().getAxis()) {
            case X -> new Vec3d(plane, center.y(), center.z());
            case Y -> new Vec3d(center.x(), plane, center.z());
            case Z -> new Vec3d(center.x(), center.y(), plane);
        };
    }

    private static Box box(AABB bounds) {
        return new Box(bounds.minX, bounds.maxX, bounds.minY, bounds.maxY, bounds.minZ, bounds.maxZ);
    }

    private record Crossing(TravelMessage.TravelBegin arm, ClientLevel source, ClientLevel target, Pose before, ClientTravelMotion.Carry carry,
                            Pose after, Vec3 expected, long deadline, String preparing, double scaleBefore) {
    }
}
