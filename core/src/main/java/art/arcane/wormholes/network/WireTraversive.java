package art.arcane.wormholes.network;


import art.arcane.optics.math.Vec3;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record WireTraversive(
    String frameNormal,
    String frameRight,
    String frameUp,
    double originX,
    double originY,
    double originZ,
    double pointX,
    double pointY,
    double pointZ,
    double velocityX,
    double velocityY,
    double velocityZ,
    double lookX,
    double lookY,
    double lookZ,
    boolean frontSide
) {
    public static WireTraversive fromCrossing(PlaneCrossing crossing) {
        return new WireTraversive(crossing.frame().getNormal().name(), crossing.frame().getRight().name(),
            crossing.frame().getUp().name(), crossing.origin().x(), crossing.origin().y(), crossing.origin().z(),
            crossing.point().x(), crossing.point().y(), crossing.point().z(),
            crossing.velocity().x(), crossing.velocity().y(), crossing.velocity().z(),
            crossing.look().x(), crossing.look().y(), crossing.look().z(), crossing.frontSide());
    }

    public PlaneCrossing crossing() {
        return new PlaneCrossing(new Frame(Face.valueOf(frameNormal), Face.valueOf(frameRight),
            Face.valueOf(frameUp)), new Vec3(originX, originY, originZ),
            new Vec3(pointX, pointY, pointZ), new Vec3(velocityX, velocityY, velocityZ),
            new Vec3(lookX, lookY, lookZ), frontSide);
    }

    public void write(DataOutputStream out) throws IOException {
        WireCodec.writeDirection(out, frameNormal);
        WireCodec.writeDirection(out, frameRight);
        WireCodec.writeDirection(out, frameUp);
        out.writeDouble(originX);
        out.writeDouble(originY);
        out.writeDouble(originZ);
        out.writeDouble(pointX);
        out.writeDouble(pointY);
        out.writeDouble(pointZ);
        out.writeDouble(velocityX);
        out.writeDouble(velocityY);
        out.writeDouble(velocityZ);
        out.writeDouble(lookX);
        out.writeDouble(lookY);
        out.writeDouble(lookZ);
        out.writeBoolean(frontSide);
    }

    public static WireTraversive read(DataInputStream in) throws IOException {
        String frameNormal = WireCodec.readDirection(in);
        String frameRight = WireCodec.readDirection(in);
        String frameUp = WireCodec.readDirection(in);
        double originX = in.readDouble();
        double originY = in.readDouble();
        double originZ = in.readDouble();
        double pointX = in.readDouble();
        double pointY = in.readDouble();
        double pointZ = in.readDouble();
        double velocityX = in.readDouble();
        double velocityY = in.readDouble();
        double velocityZ = in.readDouble();
        double lookX = in.readDouble();
        double lookY = in.readDouble();
        double lookZ = in.readDouble();
        boolean frontSide = in.readBoolean();
        return new WireTraversive(frameNormal, frameRight, frameUp, originX, originY, originZ, pointX, pointY, pointZ, velocityX, velocityY, velocityZ, lookX, lookY, lookZ, frontSide);
    }
}
