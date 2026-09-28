package art.arcane.wormholes.network;

public record EndpointValidation(State state, String detail) {
    public enum State {
        VERIFIED,
        DESTINATION_VERIFIED,
        FAILED
    }

    public boolean accepted() {
        return state != State.FAILED;
    }
}
