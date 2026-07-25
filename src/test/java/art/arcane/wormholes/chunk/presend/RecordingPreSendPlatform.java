package art.arcane.wormholes.chunk.presend;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class RecordingPreSendPlatform implements ChunkPreSendPlatform<String, String> {
    static final String PLAYER = "player";
    static final String SOURCE_WORLD = "source";
    static final String DESTINATION_WORLD = "destination";

    private final List<ChunkCoordinate> announced = new ArrayList<>();
    private final List<Sent> sent = new ArrayList<>();
    private final List<Continuation> scheduled = new ArrayList<>();
    private final List<Box> ownershipQueries = new ArrayList<>();
    private final Set<ChunkCoordinate> unloaded = new HashSet<>();
    private final Set<ChunkCoordinate> alreadySent = new HashSet<>();
    private final Set<ChunkCoordinate> foreignRegion = new HashSet<>();

    private boolean supported = true;
    private boolean online = true;
    private String world = SOURCE_WORLD;
    private int chunkX;
    private int chunkZ;
    private int clientViewDistance = 10;
    private boolean regionOwned = true;
    private boolean announceAccepted = true;
    private boolean scheduleAccepted = true;
    private int sendFailsAfter = Integer.MAX_VALUE;
    private long clock;
    private long clockStep;
    private int chunkLoadedCalls;

    RecordingPreSendPlatform supported(boolean value) {
        this.supported = value;
        return this;
    }

    RecordingPreSendPlatform online(boolean value) {
        this.online = value;
        return this;
    }

    RecordingPreSendPlatform playerChunk(int x, int z) {
        this.chunkX = x;
        this.chunkZ = z;
        return this;
    }

    RecordingPreSendPlatform clientViewDistance(int value) {
        this.clientViewDistance = value;
        return this;
    }

    RecordingPreSendPlatform regionOwned(boolean value) {
        this.regionOwned = value;
        return this;
    }

    RecordingPreSendPlatform markForeignRegion(int x, int z) {
        foreignRegion.add(new ChunkCoordinate(x, z));
        return this;
    }

    RecordingPreSendPlatform releaseForeignRegion(int x, int z) {
        foreignRegion.remove(new ChunkCoordinate(x, z));
        return this;
    }

    RecordingPreSendPlatform announceAccepted(boolean value) {
        this.announceAccepted = value;
        return this;
    }

    RecordingPreSendPlatform scheduleAccepted(boolean value) {
        this.scheduleAccepted = value;
        return this;
    }

    RecordingPreSendPlatform sendFailsAfter(int value) {
        this.sendFailsAfter = value;
        return this;
    }

    RecordingPreSendPlatform clockStep(long nanos) {
        this.clockStep = nanos;
        return this;
    }

    RecordingPreSendPlatform markUnloaded(int x, int z) {
        unloaded.add(new ChunkCoordinate(x, z));
        return this;
    }

    RecordingPreSendPlatform markAlreadySent(int x, int z) {
        alreadySent.add(new ChunkCoordinate(x, z));
        return this;
    }

    List<ChunkCoordinate> announced() {
        return announced;
    }

    List<Sent> sent() {
        return sent;
    }

    List<Continuation> scheduled() {
        return scheduled;
    }

    List<Box> ownershipQueries() {
        return ownershipQueries;
    }

    int chunkLoadedCalls() {
        return chunkLoadedCalls;
    }

    void runScheduled() {
        List<Continuation> pending = new ArrayList<>(scheduled);
        scheduled.clear();
        for (Continuation continuation : pending) {
            continuation.command().run();
        }
    }

    @Override
    public boolean supported() {
        return supported;
    }

    @Override
    public boolean online(String player) {
        return online;
    }

    @Override
    public String world(String player) {
        return world;
    }

    @Override
    public int chunkX(String player) {
        return chunkX;
    }

    @Override
    public int chunkZ(String player) {
        return chunkZ;
    }

    @Override
    public int clientViewDistance(String player) {
        return clientViewDistance;
    }

    @Override
    public boolean chunkLoaded(String world, int chunkX, int chunkZ) {
        chunkLoadedCalls++;
        return !unloaded.contains(new ChunkCoordinate(chunkX, chunkZ));
    }

    @Override
    public boolean regionOwned(String world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        ownershipQueries.add(new Box(minChunkX, minChunkZ, maxChunkX, maxChunkZ));
        if (!regionOwned) {
            return false;
        }
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                if (foreignRegion.contains(new ChunkCoordinate(x, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean alreadySent(String player, int chunkX, int chunkZ) {
        return alreadySent.contains(new ChunkCoordinate(chunkX, chunkZ));
    }

    @Override
    public boolean announceViewCenter(String player, int chunkX, int chunkZ) {
        if (!announceAccepted) {
            return false;
        }
        announced.add(new ChunkCoordinate(chunkX, chunkZ));
        return true;
    }

    @Override
    public boolean sendChunk(String player, String world, int chunkX, int chunkZ) {
        if (sent.size() >= sendFailsAfter) {
            return false;
        }
        sent.add(new Sent(world, new ChunkCoordinate(chunkX, chunkZ), announced.size()));
        return true;
    }

    @Override
    public boolean scheduleForRegion(String world, int chunkX, int chunkZ, Runnable command, long delayTicks) {
        if (!scheduleAccepted) {
            return false;
        }
        scheduled.add(new Continuation(world, new ChunkCoordinate(chunkX, chunkZ), command));
        return true;
    }

    @Override
    public long nanoTime() {
        long now = clock;
        clock += clockStep;
        return now;
    }

    record Sent(String world, ChunkCoordinate coordinate, int announcementsBefore) {
    }

    record Continuation(String world, ChunkCoordinate coordinate, Runnable command) {
    }

    record Box(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
    }
}
