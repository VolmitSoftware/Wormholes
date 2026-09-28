package art.arcane.wormholes.portal;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

import java.util.List;

public interface PortalAperture {
    AxisAlignedBB getArea();

    GeometryVector getApertureCenter();

    List<AxisAlignedBB> getCachedApertureFaces(Direction face);
}
