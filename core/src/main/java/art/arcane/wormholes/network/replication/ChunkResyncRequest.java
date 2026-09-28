package art.arcane.wormholes.network.replication;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public record ChunkResyncRequest(ReplicationStreamKey stream, long expectedSequence) {
    public void writeTo(DataOutputStream out) throws IOException {
        stream.writeTo(out);
        ReplicationVarint.writeULong(out, expectedSequence);
    }

    public static ChunkResyncRequest read(DataInputStream in) throws IOException {
        ReplicationStreamKey stream = ReplicationStreamKey.read(in);
        long expectedSequence = ReplicationVarint.readULong(in);
        return new ChunkResyncRequest(stream, expectedSequence);
    }
}
