package art.arcane.wormholes.network.replication;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public record ChunkHashProbe(List<ChunkHashEntry> entries) {
    public static final int MAX_ENTRIES = 1024;

    public record ChunkHashEntry(ReplicationStreamKey stream, long sequence, long hash) {
        public void writeTo(DataOutputStream out) throws IOException {
            stream.writeTo(out);
            ReplicationVarint.writeULong(out, sequence);
            out.writeLong(hash);
        }

        public static ChunkHashEntry read(DataInputStream in) throws IOException {
            ReplicationStreamKey stream = ReplicationStreamKey.read(in);
            long sequence = ReplicationVarint.readULong(in);
            long hash = in.readLong();
            return new ChunkHashEntry(stream, sequence, hash);
        }
    }

    public void writeTo(DataOutputStream out) throws IOException {
        ReplicationVarint.writeUInt(out, entries.size());
        for (ChunkHashEntry entry : entries) {
            entry.writeTo(out);
        }
    }

    public static ChunkHashProbe read(DataInputStream in) throws IOException {
        int count = ReplicationVarint.readUInt(in);
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IOException("Invalid chunk hash probe entry count: " + count);
        }
        List<ChunkHashEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(ChunkHashEntry.read(in));
        }
        return new ChunkHashProbe(entries);
    }
}
