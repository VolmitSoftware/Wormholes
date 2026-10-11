package art.arcane.wormholes.clientgametest;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.ClientSeamlessTravel;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class LookContinuityTravelTap {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final List<Attempt> ATTEMPTS = new ArrayList<>();

    private static boolean active;
    private static Attempt pending;

    private LookContinuityTravelTap() {
    }

    public static void open() {
        ATTEMPTS.clear();
        pending = null;
        active = true;
    }

    public static void close() {
        active = false;
        pending = null;
        ATTEMPTS.clear();
    }

    public static List<Attempt> attempts() {
        return List.copyOf(ATTEMPTS);
    }

    public static boolean active() {
        return active;
    }

    public static void begin(ClientSeamlessTravel travel, NativeCall call) {
        if (!active) {
            return;
        }
        TravelMessage.TravelBegin arm = call.arm();
        ClientLevel source = call.client().level;
        ClientLevel destination = arm.resident() ? travel.residents().level(arm.levelHandle()) : source;
        Vec3d arrival = arm.sourceToDestination().point(new Vec3d(call.player().getX(), call.player().getY(), call.player().getZ()));
        boolean ready = destination != null && destination.dimension().identifier().toString().equals(arm.world().dimension())
            && destination.getChunkSource().getChunk(arrival.blockX() >> 4, arrival.blockZ() >> 4, ChunkStatus.FULL, false) != null;
        Motion motion = new Motion(call.partial(), call.previous(), call.eye(), call.player().getPosition(call.partial()),
            call.player().getEyePosition(call.partial()));
        pending = new Attempt(arm, source, destination, call.player(), ready, false, null, null, motion);
        LOGGER.info("[LookContinuity] native crossing begin: source {} token {} generation {}, resident {} handle {}, levels {} -> {}, player {}, ready {}",
            arm.sourcePortal(), arm.token(), arm.generation(), arm.resident(), arm.levelHandle(), System.identityHashCode(source),
            System.identityHashCode(destination), System.identityHashCode(call.player()), ready);
        LOGGER.info("[LookContinuity] native crossing motion: partial {}, previous {}, eye {}, sampled feet {}, sampled eye {}, geometry {}",
            motion.partial(), motion.previous(), motion.eye(), motion.feet(), motion.sampledEye(), arm.sourceGeometry());
    }

    public static void finish(boolean successful) {
        if (!active || pending == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        ATTEMPTS.add(new Attempt(pending.arm(), pending.source(), pending.destination(), pending.player(), pending.ready(), successful,
            client.level, client.player, pending.motion()));
        pending = null;
    }

    public record NativeCall(Minecraft client, LocalPlayer player, TravelMessage.TravelBegin arm, float partial, Vec3 previous, Vec3 eye) {
    }

    public record Motion(float partial, Vec3 previous, Vec3 eye, Vec3 feet, Vec3 sampledEye) {
    }

    public record Attempt(TravelMessage.TravelBegin arm, ClientLevel source, ClientLevel destination, LocalPlayer player, boolean ready,
                          boolean successful, ClientLevel arrivedLevel, LocalPlayer arrivedPlayer, Motion motion) {
    }
}
