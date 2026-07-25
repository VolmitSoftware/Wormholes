package art.arcane.wormholes.chunk.presend;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record ChunkPreSendRollback(int sourceCenterX, int sourceCenterZ, List<ChunkCoordinate> resend) {
    public ChunkPreSendRollback {
        resend = List.copyOf(Objects.requireNonNull(resend, "resend"));
    }

    public static ChunkPreSendRollback none(int sourceCenterX, int sourceCenterZ) {
        return new ChunkPreSendRollback(sourceCenterX, sourceCenterZ, List.of());
    }

    public static ChunkPreSendRollback of(
        ClientChunkWindow sourceWindow,
        List<ChunkCoordinate> sentDestinationChunks,
        boolean sameWorld
    ) {
        ClientChunkWindow window = Objects.requireNonNull(sourceWindow, "sourceWindow");
        List<ChunkCoordinate> sent = Objects.requireNonNull(sentDestinationChunks, "sentDestinationChunks");
        LinkedHashSet<ChunkCoordinate> clobbered = new LinkedHashSet<>();
        for (ChunkCoordinate destination : sent) {
            ChunkCoordinate occupant = window.occupantOf(destination.x(), destination.z());
            if (sameWorld && occupant.equals(destination)) {
                continue;
            }
            clobbered.add(occupant);
        }
        List<ChunkCoordinate> ordered = new ArrayList<>(clobbered);
        ordered.sort(ChunkCoordinate.nearestFirst(window.centerX(), window.centerZ()));
        return new ChunkPreSendRollback(window.centerX(), window.centerZ(), ordered);
    }

    public boolean empty() {
        return resend.isEmpty();
    }

    public int size() {
        return resend.size();
    }
}
