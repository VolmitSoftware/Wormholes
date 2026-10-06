package art.arcane.optics.client;

import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.math.Face;

public final class ClientViewBlockTransform {
    private final Face xAxis;
    private final Face yAxis;
    private final Face zAxis;
    private final int offsetX;
    private final int offsetY;
    private final int offsetZ;

    public ClientViewBlockTransform(ProjectionEnvironment.Transform transform) {
        xAxis = transform.xAxis();
        yAxis = transform.yAxis();
        zAxis = transform.zAxis();
        offsetX = offset(transform.translation().x(), xAxis.x() + yAxis.x() + zAxis.x());
        offsetY = offset(transform.translation().y(), xAxis.y() + yAxis.y() + zAxis.y());
        offsetZ = offset(transform.translation().z(), xAxis.z() + yAxis.z() + zAxis.z());
    }

    public int displayX(int x, int y, int z) {
        return x * xAxis.x() + y * yAxis.x() + z * zAxis.x() + offsetX;
    }

    public int displayY(int x, int y, int z) {
        return x * xAxis.y() + y * yAxis.y() + z * zAxis.y() + offsetY;
    }

    public int displayZ(int x, int y, int z) {
        return x * xAxis.z() + y * yAxis.z() + z * zAxis.z() + offsetZ;
    }

    public int destinationX(int x, int y, int z) {
        return (x - offsetX) * xAxis.x() + (y - offsetY) * xAxis.y() + (z - offsetZ) * xAxis.z();
    }

    public int destinationY(int x, int y, int z) {
        return (x - offsetX) * yAxis.x() + (y - offsetY) * yAxis.y() + (z - offsetZ) * yAxis.z();
    }

    public int destinationZ(int x, int y, int z) {
        return (x - offsetX) * zAxis.x() + (y - offsetY) * zAxis.y() + (z - offsetZ) * zAxis.z();
    }

    private static int offset(double translation, int direction) {
        return (int) (direction > 0 ? Math.ceil(translation - 0.5D) : Math.floor(translation - 0.5D));
    }
}
