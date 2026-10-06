package art.arcane.wormholes.portal;

import java.util.UUID;

import art.arcane.optics.aperture.Endpoint;
import art.arcane.wormholes.geometry.GeometryVector;

import art.arcane.wormholes.util.Direction;

public interface IPortal extends Endpoint {
    public Direction getDirection();

    public PortalFrame getFrame();

    public UUID getId();

    public String getName();

    public void setName(String name);

    public boolean isRemote();

    public GeometryVector getOrigin();

    @Override
    default UUID id() {
        return getId();
    }
}
