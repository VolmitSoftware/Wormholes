package art.arcane.optics.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.PortalCoordMap;
import art.arcane.optics.aperture.ApertureDescriptor;

public final class ClientSpace {
    public static final ClientSpace IDENTITY = new ClientSpace(new double[] {1.0D, 0.0D, 0.0D, 0.0D, 1.0D, 0.0D, 0.0D, 0.0D, 1.0D},
        0.0D, 0.0D, 0.0D, List.of());
    private static final double SNAP_TOLERANCE = 1.0E-9D;

    private final double[] linear;
    private final double translateX;
    private final double translateY;
    private final double translateZ;
    private final List<ApertureDescriptor> reflections;

    private ClientSpace(double[] linear, double translateX, double translateY, double translateZ, List<ApertureDescriptor> reflections) {
        this.linear = linear;
        this.translateX = translateX;
        this.translateY = translateY;
        this.translateZ = translateZ;
        this.reflections = reflections;
    }

    public static ClientSpace mirror(ApertureDescriptor mirror) {
        return IDENTITY.throughMirror(mirror);
    }

    public ClientSpace throughMirror(ApertureDescriptor mirror) {
        Objects.requireNonNull(mirror, "mirror");
        Frame frame = mirror.frame();
        int quarterTurns = mirror.mirrorQuarterTurns();
        Vec3d origin = mirror.apertureArea().center();
        double[] column = new double[3];
        double[] mirrorLinear = new double[9];
        for (int axis = 0; axis < 3; axis++) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(axis == 0 ? 1.0D : 0.0D, axis == 1 ? 1.0D : 0.0D, axis == 2 ? 1.0D : 0.0D,
                frame, quarterTurns, column);
            mirrorLinear[axis] = column[0];
            mirrorLinear[3 + axis] = column[1];
            mirrorLinear[6 + axis] = column[2];
        }
        double mirrorX = origin.getX() - (mirrorLinear[0] * origin.getX() + mirrorLinear[1] * origin.getY() + mirrorLinear[2] * origin.getZ());
        double mirrorY = origin.getY() - (mirrorLinear[3] * origin.getX() + mirrorLinear[4] * origin.getY() + mirrorLinear[5] * origin.getZ());
        double mirrorZ = origin.getZ() - (mirrorLinear[6] * origin.getX() + mirrorLinear[7] * origin.getY() + mirrorLinear[8] * origin.getZ());
        double[] composed = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                composed[row * 3 + col] = linear[row * 3] * mirrorLinear[col] + linear[row * 3 + 1] * mirrorLinear[3 + col]
                    + linear[row * 3 + 2] * mirrorLinear[6 + col];
            }
        }
        double composedX = linear[0] * mirrorX + linear[1] * mirrorY + linear[2] * mirrorZ + translateX;
        double composedY = linear[3] * mirrorX + linear[4] * mirrorY + linear[5] * mirrorZ + translateY;
        double composedZ = linear[6] * mirrorX + linear[7] * mirrorY + linear[8] * mirrorZ + translateZ;
        List<ApertureDescriptor> chain = new ArrayList<ApertureDescriptor>(reflections.size() + 1);
        chain.add(mirror);
        chain.addAll(reflections);
        return new ClientSpace(composed, composedX, composedY, composedZ, List.copyOf(chain));
    }

    public boolean identity() {
        return reflections.isEmpty();
    }

    public List<ApertureDescriptor> reflections() {
        return reflections;
    }

    public void toDisplay(double x, double y, double z, double[] out3) {
        out3[0] = linear[0] * x + linear[1] * y + linear[2] * z + translateX;
        out3[1] = linear[3] * x + linear[4] * y + linear[5] * z + translateY;
        out3[2] = linear[6] * x + linear[7] * y + linear[8] * z + translateZ;
    }

    public void entityToDisplay(double x, double y, double z, double height, double[] out3) {
        double halfHeight = height * 0.5D;
        toDisplay(x, y + halfHeight, z, out3);
        out3[1] -= halfHeight;
    }

    public void vectorToDisplay(double x, double y, double z, double[] out3) {
        out3[0] = linear[0] * x + linear[1] * y + linear[2] * z;
        out3[1] = linear[3] * x + linear[4] * y + linear[5] * z;
        out3[2] = linear[6] * x + linear[7] * y + linear[8] * z;
    }

    public void toContent(double x, double y, double z, double[] out3) {
        double relX = x - translateX;
        double relY = y - translateY;
        double relZ = z - translateZ;
        out3[0] = linear[0] * relX + linear[3] * relY + linear[6] * relZ;
        out3[1] = linear[1] * relX + linear[4] * relY + linear[7] * relZ;
        out3[2] = linear[2] * relX + linear[5] * relY + linear[8] * relZ;
    }

    public void displayCell(int x, int y, int z, int[] out3, double[] scratch3) {
        toDisplay(x + 0.5D, y + 0.5D, z + 0.5D, scratch3);
        out3[0] = cell(scratch3[0]);
        out3[1] = cell(scratch3[1]);
        out3[2] = cell(scratch3[2]);
    }

    public void contentCell(int x, int y, int z, int[] out3, double[] scratch3) {
        toContent(x + 0.5D, y + 0.5D, z + 0.5D, scratch3);
        out3[0] = cell(scratch3[0]);
        out3[1] = cell(scratch3[1]);
        out3[2] = cell(scratch3[2]);
    }

    private static int cell(double value) {
        double nearest = Math.rint(value);
        return (int) Math.floor(Math.abs(value - nearest) <= SNAP_TOLERANCE ? nearest : value);
    }
}
