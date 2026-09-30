package art.arcane.wormholes.portal.rtp;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.ProjectorPassRevision;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class RtpProjectionGeometry {
    private RtpProjectionGeometry() {
    }

    public static RtpProjectionView.ReadyData create(Source source, RtpDestination destination, long routeRevision) {
        PortalFrame frame = source.frame();
        PortalFrame targetFrame = targetFrameFor(frame);
        RtpProjectionView.SourceFrame origin = new RtpProjectionView.SourceFrame(source.worldKey(),
            point(source.center().x(), source.center().y(), source.center().z()), vector(frame.getRight()), vector(frame.getUp()),
            vector(frame.getNormal().reverse()), axisSpan(source.area(), frame.getRight()), axisSpan(source.area(), frame.getUp()), source.revision());
        RtpProjectionView.Target target = new RtpProjectionView.Target(destination.worldKey(),
            point(destination.blockX() + 0.5D, destination.feetY() + previewAnchorLift(source.center(), source.area()), destination.blockZ() + 0.5D),
            vector(targetFrame.getRight()), vector(targetFrame.getUp()), vector(targetFrame.getNormal().reverse()));
        return new RtpProjectionView.ReadyData(routeId(source.id(), destination), routeRevision, origin, target);
    }

    public static long plateIdentity(UUID worldId, double x, double y, double z, PortalFrame frame, long routeRevision) {
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

    public static double previewAnchorLift(GeometryVector center, AxisAlignedBB area) {
        return center == null || area == null ? 1D : Math.max(1D, center.y() - area.getYa());
    }

	public static PortalFrame targetFrameFor(PortalFrame sourceFrame)
	{
		Direction sourceNormal = sourceFrame.getNormal();
		Direction horizontal = sourceNormal.isVertical() ? sourceFrame.getUp() : sourceNormal;
		if(horizontal.isVertical())
		{
			horizontal = Direction.N;
		}
		return PortalFrame.fromNormalUp(horizontal, Direction.U);
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

    private static RtpProjectionView.Vector3 vector(Direction direction) {
        return new RtpProjectionView.Vector3(direction.x(), direction.y(), direction.z());
    }

    private static double axisSpan(AxisAlignedBB area, Direction direction) {
        return area == null ? 1D : Math.max(1D, Math.abs(direction.x()) * area.sizeX()
            + Math.abs(direction.y()) * area.sizeY() + Math.abs(direction.z()) * area.sizeZ());
    }

    public record Source(UUID id, String worldKey, GeometryVector center, PortalFrame frame, AxisAlignedBB area, long revision) {
    }
}
