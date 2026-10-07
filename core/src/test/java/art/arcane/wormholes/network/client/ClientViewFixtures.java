package art.arcane.wormholes.network.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.crossing.MomentumRule;
import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamCodec;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessage;

public final class ClientViewFixtures {
    static final ViewStreamCodec CODEC = new ViewStreamCodec(List.of(FxExtension.INSTANCE, new TravelExtension(SeamlessTravelCodec.INSTANCE)));

    record Vector(String name, ViewStreamMessage message, long caps, int seq, int flags) {
        boolean clientbound() {
            return ClientViewFixtures.CODEC.clientbound(message);
        }

        TravelMessage travel() {
            return (TravelMessage) ((ViewStreamMessage.Extension) message).payload();
        }
    }

    private ClientViewFixtures() {
    }

    static List<Vector> vectors() {
        List<Vector> out = new ArrayList<Vector>();
        out.add(new Vector("fx", FxExtension.INSTANCE.wrap(fx()), ViewStreamCapability.ALL, 11, ViewStreamLimits.FLAG_LAST));
        out.addAll(travelVectors());
        return out;
    }

    static List<Vector> travelVectors() {
        List<Vector> out = new ArrayList<Vector>();
        out.add(travel("travel_begin", travelBegin(), ViewStreamCapability.ALL, 18, 0));
        out.add(travel("travel_chunk", new TravelMessage.TravelChunk(new UUID(12, 34), 3L, -32, -10, 2, 0, 1,
            4, new byte[] {1, 2, 3, 4}), ViewStreamCapability.ALL, 19, 0));
        out.add(travel("travel_end", new TravelMessage.TravelEnd(new UUID(12, 34), 3L, 9L,
            List.of(new TravelMessage.TravelChunkRevision(-32, -10, 2))), ViewStreamCapability.ALL, 20, ViewStreamLimits.FLAG_LAST));
        out.add(travel("travel_ready", new TravelMessage.TravelReady(new UUID(12, 34), 3L, 9L), ViewStreamCapability.NONE, 0, 0));
        out.add(travel("travel_commit", new TravelMessage.TravelCommit(new UUID(12, 34), 3L, 9L,
            "minecraft:the_nether", "minecraft:overworld", travelBegin().arrival(), new Vec3d(0.25D, -0.5D, 1.0D)), ViewStreamCapability.ALL, 21,
            ViewStreamLimits.FLAG_LAST));
        out.add(travel("travel_cancel", new TravelMessage.TravelCancel(new UUID(12, 34), 3L), ViewStreamCapability.ALL, 22,
            ViewStreamLimits.FLAG_LAST));
        out.add(travel("travel_cross", new TravelMessage.TravelCross(new UUID(12, 34), 3L, 9L,
            new TravelMessage.TravelPose(635.5D, 65.0D, -4681.4D, 90.0F, -12.0F),
            new Vec3d(635.5D, 66.62D, -4681.6D), new Vec3d(635.5D, 66.62D, -4681.4D)), ViewStreamCapability.NONE, 0, 0));
        byte[] travelHash = new byte[32];
        for (int index = 0; index < travelHash.length; index++) {
            travelHash[index] = (byte) index;
        }
        out.add(travel("travel_reuse", new TravelMessage.TravelReuse(new UUID(12, 34), 3L, -32, -10, 2, travelHash),
            ViewStreamCapability.ALL, 23, ViewStreamLimits.FLAG_LAST));
        out.add(travel("travel_cached", new TravelMessage.TravelCached(new UUID(12, 34), 3L, -32, -10, 2, travelHash, true),
            ViewStreamCapability.NONE, 0, 0));
        out.add(travel("travel_begin_seamless", seamlessBegin(), ViewStreamCapability.ALL, 25, 0));
        out.add(travel("remote_level_open", new TravelMessage.RemoteLevelOpen(4, travelBegin().world(), travelBegin().environment(), 8,
            new TravelMessage.TravelCoordinate(-32, -10)), ViewStreamCapability.ALL, 26, 0));
        out.add(travel("remote_level_close", new TravelMessage.RemoteLevelClose(4), ViewStreamCapability.ALL, 27, ViewStreamLimits.FLAG_LAST));
        out.add(travel("routed_packet", new TravelMessage.RoutedPacket(4, 17, 0, 1, 4, new byte[] {5, 6, 7, 8}),
            ViewStreamCapability.ALL, 28, 0));
        out.add(travel("travel_accept", new TravelMessage.TravelAccept(new UUID(12, 34), 3L, 9L, travelBegin().arrival(),
            new Vec3d(0.25D, -0.5D, 1.0D), 4, true, 1200L), ViewStreamCapability.ALL, 29, ViewStreamLimits.FLAG_LAST));
        out.add(travel("remote_view_ack", new TravelMessage.RemoteViewAck(4, 17, 8), ViewStreamCapability.NONE, 0, 0));
        return out;
    }

    static TravelMessage.TravelBegin seamlessBegin() {
        TravelMessage.TravelBegin base = travelBegin();
        return new TravelMessage.TravelBegin(base.token(), base.generation(), base.sourcePortal(), base.sourceWorld(), base.sourceGeometry(),
            base.destinationToSource(), base.world(), base.arrival(), base.chunks(), base.environment(), base.expiresMillis(),
            new TravelMessage.ArrivalRules(OrientationRule.LOOK, true, new MomentumRule(MomentumRule.Mode.SCALE, 0.75D, 3.5D,
                new Vec3d(0.0D, 0.25D, 0.0D))), true, 4, true);
    }

    public static TravelMessage.TravelBegin travelBegin() {
        ProjectionEnvironment base = environment();
        ProjectionEnvironment.World world = base.world();
        ProjectionEnvironment environment = new ProjectionEnvironment(base.gameTime(), base.sky(), base.fog(), base.lighting(),
            base.clouds(), OpticTransform.IDENTITY, base.dimension(),
            new ProjectionEnvironment.World("minecraft:overworld", world.clockTime(), world.biomeKey(), world.seaLevel(),
                world.blockLight(), world.skyLight(), world.logicalHeight(), world.hasCeiling(), world.ambientLight(),
                world.eyeMedium(), world.hasFixedTime()));
        return new TravelMessage.TravelBegin(new UUID(12, 34), 3L, new UUID(56, 78), "minecraft:the_nether",
            geometry(), OpticTransform.of(AxisPermutation.of(Face.S, Face.U, Face.E), 4, 0, 6),
            new TravelMessage.TravelWorld("minecraft:overworld", "minecraft:overworld", 123456789L, false, true, 63, -64, 384),
            new TravelMessage.TravelPose(-511.5D, 81.0D, -159.5D, 90.0F, -12.0F),
            List.of(new TravelMessage.TravelCoordinate(-32, -10)), environment, 30_000, TravelMessage.ArrivalRules.FRAME, false, 0, false);
    }

    static ProjectionEnvironment environment() {
        ProjectionEnvironment.Color color = new ProjectionEnvironment.Color(0.125F, 0.5F, 1.25F);
        ProjectionEnvironment.ColorAlpha alpha = new ProjectionEnvironment.ColorAlpha(0.75F, 0.5F, 0.25F, 0.5F);
        return new ProjectionEnvironment(18000L,
            new ProjectionEnvironment.Sky(ProjectionEnvironment.Skybox.OVERWORLD, 1.5F, 2.5F, 3.5F, 0.8F, alpha, color, 5, 0.25F, 0.5F),
            new ProjectionEnvironment.Fog(color, -8.0F, 96.0F, 512.0F, 256.0F, color, 0.0F, 32.0F),
            new ProjectionEnvironment.Lighting(color, 0.75F, color, color), new ProjectionEnvironment.Clouds(alpha, 192.0F),
            OpticTransform.of(AxisPermutation.of(Face.N, Face.U, Face.E), -128.5D, 96.0D, 33.25D),
            new ProjectionEnvironment.Dimension(-64, 384, true, ProjectionEnvironment.CardinalLighting.DEFAULT, 63.0D, false),
            new ProjectionEnvironment.World("test:destination", 72000L, "minecraft:plains", 63, 7, 15, 256, true, 0.1F, ProjectionEnvironment.EyeMedium.WATER, true));
    }

    static ApertureDescriptor geometry() {
        boolean[] open = new boolean[3 * 3];
        for (int i = 0; i < open.length; i++) {
            open[i] = i != 4;
        }
        return new ApertureDescriptor(635, 64, -4682, 3, true, 0, false, 3, 3, ApertureDescriptor.apertureMask(3, 3, open),
            0.25F, 0.75F, 1.2F, 64, 1, 1, 6, 0, 1, ApertureDescriptor.FIDELITY_DISPLAY_ENTITIES | ApertureDescriptor.FIDELITY_WEATHER,
            ApertureDescriptor.KIND_RTP, 0.0D, 0, 0x7A7A7A7A7A7A7A7AL, List.of());
    }

    static FxMessage.Fx fx() {
        return new FxMessage.Fx(7, List.of(
            new FxMessage.FxEmitter(FxMessage.FxKind.RIM_DUST, "minecraft:portal", 635.5D, 64.5D, -4681.5D, 1.0F, 0.0F, 5, 0),
            new FxMessage.FxEmitter(FxMessage.FxKind.SOUND, "minecraft:block.portal.ambient", 636.0D, 65.0D, -4681.0D, 0.5F, 1.1F, 0, 1),
            new FxMessage.FxEmitter(FxMessage.FxKind.ANIMATION, "", 637.5D, 66.0D, -4686.5D, 3.0F, 4.0F, 0, 0x42),
            new FxMessage.FxEmitter(FxMessage.FxKind.BURST, "minecraft:reverse_portal", 637.5D, 66.0D, -4686.5D, 0.4F, 0.6F, 12, 40)));
    }

    private static Vector travel(String name, TravelMessage message, long caps, int seq, int flags) {
        return new Vector(name, TravelExtension.PREPARED.wrap(message), caps, seq, flags);
    }
}
