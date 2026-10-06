package art.arcane.wormholes.portal;

import java.util.UUID;

import art.arcane.optics.aperture.Endpoint;
import art.arcane.optics.math.Vec3;

import art.arcane.optics.math.Face;
import art.arcane.optics.frame.Frame;

public interface IPortal extends Endpoint {
    public Face getDirection();

    public Frame getFrame();

    public UUID getId();

    public String getName();

    public void setName(String name);

    public boolean isRemote();

    public Vec3 getOrigin();

    @Override
    default UUID id() {
        return getId();
    }
}
