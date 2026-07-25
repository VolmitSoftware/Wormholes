package art.arcane.wormholes.chunk;

public enum ChunkSendRateLimit {
    SEND("chunkLoadingBasic", "playerMaxChunkSendRate"),
    LOAD("chunkLoadingBasic", "playerMaxChunkLoadRate");

    private final String section;
    private final String field;

    ChunkSendRateLimit(String section, String field) {
        this.section = section;
        this.field = field;
    }

    public String section() {
        return section;
    }

    public String field() {
        return field;
    }

    public String label() {
        return switch (this) {
            case SEND -> "send";
            case LOAD -> "load";
        };
    }
}
