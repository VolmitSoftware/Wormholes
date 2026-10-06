package art.arcane.wormholes.portal;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.math.Face;

import java.util.Objects;
import java.util.UUID;
import art.arcane.optics.frame.Frame;

public abstract class Portal implements IPortal {
    protected Face direction;
    private Frame frame;
    private boolean explicitFrame;
    private UUID id;
    private Vec3d origin;
    private String name;

    public Portal(UUID id, Vec3d origin) {
        this.id = Objects.requireNonNull(id, "id");
        this.origin = Objects.requireNonNull(origin, "origin");
        frame = Frame.canonical(Face.N);
        direction = frame.getNormal();
        name = "Portal " + id.toString().substring(0, 4);
    }

    @Override
    public Vec3d getOrigin() {
        return origin;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public Face getDirection() {
        return frame.getNormal();
    }

    @Override
    public Frame getFrame() {
        return frame;
    }

    protected boolean hasExplicitFrame() {
        return explicitFrame;
    }

    protected void restore(State state) {
        Objects.requireNonNull(state, "state");
        id = state.id();
        origin = state.origin();
        name = state.name();
        explicitFrame = state.explicitFrame();
        applyFrame(state.frame());
    }

    protected void applyFrame(Frame frame) {
        this.frame = Objects.requireNonNull(frame, "frame");
        direction = frame.getNormal();
    }

    public record State(UUID id, Vec3d origin, String name, Frame frame, boolean explicitFrame) {
        public State {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(frame, "frame");
        }
    }
}
