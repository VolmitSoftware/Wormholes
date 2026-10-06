package art.arcane.wormholes.portal.rtp;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.scan.ProjectorPassRevision;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class RtpProjectionGeometry {
    private RtpProjectionGeometry() {
    }

    public static RtpProjectionView.ReadyData create(Source source, RtpDestination destination, long routeRevision) {
        Frame frame = source.frame();
        Frame targetFrame = targetFrameFor(frame);
        RtpProjectionView.SourceFrame origin = new RtpProjectionView.SourceFrame(source.worldKey(),
            point(source.center().x(), source.center().y(), source.center().z()), vector(frame.getRight()), vector(frame.getUp()),
            vector(frame.getNormal().reverse()), axisSpan(source.area(), frame.getRight()), axisSpan(source.area(), frame.getUp()), source.revision());
        RtpProjectionView.Target target = new RtpProjectionView.Target(destination.worldKey(),
            point(destination.blockX() + 0.5D, destination.feetY() + previewAnchorLift(source.center(), source.area()), destination.blockZ() + 0.5D),
            vector(targetFrame.getRight()), vector(targetFrame.getUp()), vector(targetFrame.getNormal().reverse()));
        return new RtpProjectionView.ReadyData(routeId(source.id(), destination), routeRevision, origin, target);
    }

    public static long plateIdentity(UUID worldId, double x, double y, double z, Frame frame, long routeRevision) {
        long identity = ProjectorPassRevision.mix(1469598103934665603L, worldId.getMostSignificantBits());
        identity = ProjectorPassRevision.mix(identity, worldId.getLeastSignificantBits());
        identity = ProjectorPassRevision.mix(identity, Double.doubleToLongBits(x));
        identity = ProjectorPassRevision.mix(identity, Double.doubleToLongBits(y));
        identity = ProjectorPassRevision.mix(identity, Double.doubleToLongBits(z));
        identity = ProjectorPassRevision.mix(identity, frame.getNormal().ordinal());
        identity = ProjectorPassRevision.mix(identity, frame.getRight().ordinal());
        identity = ProjectorPassRevision.mix(identity, frame.getUp().ordinal());
        identity = ProjectorPassRevision.mix(identity, routeRevision);
        return identity == 0L ? 1L : identity;
    }

    public static double previewAnchorLift(Vec3d center, Box area) {
        return center == null || area == null ? 1D : Math.max(1D, center.y() - area.getYa());
    }

	public static Frame targetFrameFor(Frame sourceFrame)
	{
		Face sourceNormal = sourceFrame.getNormal();
		Face horizontal = sourceNormal.isVertical() ? sourceFrame.getUp() : sourceNormal;
		if(horizontal.isVertical())
		{
			horizontal = Face.N;
		}
		return Frame.fromNormalUp(horizontal, Face.U);
	}
	public static UUID routeId(UUID portalId, RtpDestination destination)
	{
		String value = portalId + ":" + destination.worldKey() + ":" + destination.blockX() + ":"
				+ destination.feetY() + ":" + destination.blockZ() + ":" + destination.generation() + ":" + destination.attempt();
		return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
	}
    private static RtpProjectionView.Point3 point(double x, double y, double z) {
        return new RtpProjectionView.Point3(x, y, z);
    }

    private static RtpProjectionView.Vector3 vector(Face direction) {
        return new RtpProjectionView.Vector3(direction.x(), direction.y(), direction.z());
    }

    private static double axisSpan(Box area, Face direction) {
        return area == null ? 1D : Math.max(1D, Math.abs(direction.x()) * area.sizeX()
            + Math.abs(direction.y()) * area.sizeY() + Math.abs(direction.z()) * area.sizeZ());
    }

    public record Source(UUID id, String worldKey, Vec3d center, Frame frame, Box area, long revision) {
    }
}
