package art.arcane.wormholes.network.replication;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record ChunkBulk(ReplicationStreamKey stream, long sequence, byte[] bulkPayload) {
    public static final int MAX_BULK_PAYLOAD_BYTES = 2 * 1024 * 1024;

    public void writeTo(DataOutputStream out) throws IOException {
        stream.writeTo(out);
        ReplicationVarint.writeULong(out, sequence);
        ReplicationVarint.writeUInt(out, bulkPayload.length);
        out.write(bulkPayload);
    }

    public static ChunkBulk read(DataInputStream in) throws IOException {
        ReplicationStreamKey stream = ReplicationStreamKey.read(in);
        long sequence = ReplicationVarint.readULong(in);
        int length = ReplicationVarint.readUInt(in);
        if (length < 0 || length > MAX_BULK_PAYLOAD_BYTES) {
            throw new IOException("Invalid chunk bulk payload length: " + length);
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        return new ChunkBulk(stream, sequence, payload);
    }
}
