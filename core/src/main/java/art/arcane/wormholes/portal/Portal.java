package art.arcane.wormholes.portal;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.util.Direction;

import java.util.Objects;
import java.util.UUID;

public abstract class Portal implements IPortal {
    protected Direction direction;
    private PortalFrame frame;
    private boolean explicitFrame;
    private UUID id;
    private GeometryVector origin;
    private String name;

    public Portal(UUID id, GeometryVector origin) {
        this.id = Objects.requireNonNull(id, "id");
        this.origin = Objects.requireNonNull(origin, "origin");
        frame = PortalFrame.canonical(Direction.N);
        direction = frame.getNormal();
        name = "Portal " + id.toString().substring(0, 4);
    }

    @Override
    public GeometryVector getOrigin() {
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
    public Direction getDirection() {
        return frame.getNormal();
    }

    @Override
    public PortalFrame getFrame() {
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

    protected void applyFrame(PortalFrame frame) {
        this.frame = Objects.requireNonNull(frame, "frame");
        direction = frame.getNormal();
    }

    public record State(UUID id, GeometryVector origin, String name, PortalFrame frame, boolean explicitFrame) {
        public State {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(frame, "frame");
        }
    }
}
