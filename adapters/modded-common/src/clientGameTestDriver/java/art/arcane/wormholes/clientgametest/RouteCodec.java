package art.arcane.wormholes.clientgametest;

import art.arcane.optics.crossing.OrientationRule;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.transit.OrientationPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Base64;
import java.util.UUID;

final class RouteCodec {
    private static final String FIELDS = "\\|";
    private static final String LEG_FIELDS = ";";
    private static final String VALUES = ",";

    private RouteCodec() {
    }

    static String spec(SeamlessScenario.RouteSpec spec) {
        return level(spec.sourceLevel()) + " " + position(spec.sourceMin()) + " " + level(spec.destinationLevel()) + " "
            + position(spec.destinationMin()) + " " + spec.orientation().name();
    }

    static SeamlessScenario.RouteSpec spec(String text) {
        String[] fields = text.trim().split(" ");
        if (fields.length != 5) {
            throw new IllegalArgumentException("route spec needs 5 fields: " + text);
        }
        return new SeamlessScenario.RouteSpec(level(fields[0]), position(fields[1]), level(fields[2]), position(fields[3]),
            OrientationPolicy.valueOf(fields[4]));
    }

    static String approach(SeamlessScenario.Route route) {
        return level(route.sourceLevel()) + " " + position(route.sourceMin());
    }

    static String route(SeamlessScenario.Route route) {
        return String.join("|", route.source().toString(), route.destination().toString(), level(route.sourceLevel()),
            position(route.sourceMin()), position(route.destinationMin()), leg(route.outbound()), leg(route.inbound()),
            route.stand().toString(), route.frame().toString());
    }

    static SeamlessScenario.Route route(String text) {
        String[] fields = text.trim().split(FIELDS);
        if (fields.length != 9) {
            throw new IllegalArgumentException("route needs 9 fields: " + text);
        }
        return new SeamlessScenario.Route(UUID.fromString(fields[0]), UUID.fromString(fields[1]), level(fields[2]), position(fields[3]),
            position(fields[4]), leg(fields[5]), leg(fields[6]), UUID.fromString(fields[7]), UUID.fromString(fields[8]));
    }

    static BlockPos position(String text) {
        String[] values = text.split(VALUES);
        return new BlockPos(Integer.parseInt(values[0]), Integer.parseInt(values[1]), Integer.parseInt(values[2]));
    }

    static ResourceKey<Level> level(String text) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(text));
    }

    private static String leg(SeamlessScenario.Leg leg) {
        SeamlessScenario.Exit exit = leg.exit();
        return String.join(LEG_FIELDS, Base64.getEncoder().encodeToString(leg.toward().encode()), frame(exit.sourceView()),
            vector(exit.sourceOrigin()), frame(exit.destinationFrame()), Boolean.toString(exit.front()), leg.orientation().name());
    }

    private static SeamlessScenario.Leg leg(String text) {
        String[] fields = text.split(LEG_FIELDS);
        if (fields.length != 6) {
            throw new IllegalArgumentException("leg needs 6 fields: " + text);
        }
        SeamlessScenario.Exit exit = new SeamlessScenario.Exit(frame(fields[1]), vector(fields[2]), frame(fields[3]), Boolean.parseBoolean(fields[4]));
        return new SeamlessScenario.Leg(OpticTransform.decode(Base64.getDecoder().decode(fields[0])), exit, OrientationRule.valueOf(fields[5]));
    }

    private static String frame(Frame frame) {
        return frame.getNormal().name() + VALUES + frame.getRight().name() + VALUES + frame.getUp().name();
    }

    private static Frame frame(String text) {
        String[] faces = text.split(VALUES);
        return new Frame(Face.valueOf(faces[0]), Face.valueOf(faces[1]), Face.valueOf(faces[2]));
    }

    private static String vector(Vec3d vector) {
        return vector.x() + VALUES + vector.y() + VALUES + vector.z();
    }

    private static Vec3d vector(String text) {
        String[] values = text.split(VALUES);
        return new Vec3d(Double.parseDouble(values[0]), Double.parseDouble(values[1]), Double.parseDouble(values[2]));
    }

    private static String position(BlockPos position) {
        return position.getX() + VALUES + position.getY() + VALUES + position.getZ();
    }

    private static String level(ResourceKey<Level> level) {
        return level.identifier().toString();
    }
}
