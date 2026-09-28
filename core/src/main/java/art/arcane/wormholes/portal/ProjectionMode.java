package art.arcane.wormholes.portal;

public enum ProjectionMode {
    OFF,
    ON;

    public ProjectionMode next() {
        return this == OFF ? ON : OFF;
    }
}
