package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.portal.ProjectionRenderMode;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

public record ReplicationStreamKey(UUID portalId, UUID sourceWorldId, long chunkKey, ProjectionRenderMode renderMode) {
    public ReplicationStreamKey {
        Objects.requireNonNull(portalId);
        Objects.requireNonNull(sourceWorldId);
        Objects.requireNonNull(renderMode);
    }

    public void writeTo(DataOutputStream out) throws IOException {
        writeUuid(out, portalId);
        writeUuid(out, sourceWorldId);
        out.writeLong(chunkKey);
        out.writeByte(renderMode.ordinal());
    }

    public static ReplicationStreamKey read(DataInputStream in) throws IOException {
        UUID portalId = readUuid(in);
        UUID sourceWorldId = readUuid(in);
        long chunkKey = in.readLong();
        int renderOrdinal = in.readUnsignedByte();
        ProjectionRenderMode[] modes = ProjectionRenderMode.values();
        if (renderOrdinal >= modes.length) {
            throw new IOException("Invalid replication render mode: " + renderOrdinal);
        }
        return new ReplicationStreamKey(portalId, sourceWorldId, chunkKey, modes[renderOrdinal]);
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
