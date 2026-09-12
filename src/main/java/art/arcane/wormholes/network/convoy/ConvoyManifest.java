package art.arcane.wormholes.network.convoy;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import art.arcane.wormholes.network.WireCodec;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.WireTraversive;

/**
 * A rig offered to a destination server: members in dependency order with their entity snapshots,
 * vehicle and leash edges, and each member's own crossing geometry. The transferring player is the
 * {@code rider} member; it carries no snapshot because it arrives by client transfer.
 */
public record ConvoyManifest(UUID groupId, UUID destPortalId, List<Member> members) {
    public static final int MAX_MEMBERS = 64;

    public record Member(UUID entityId, byte[] snapshot, UUID vehicleId, UUID leashHolderId, WireTraversive traversive, boolean rider) {
        public Member {
            Objects.requireNonNull(entityId, "entityId");
            snapshot = snapshot == null ? new byte[0] : snapshot;
            Objects.requireNonNull(traversive, "traversive");
        }
    }

    public ConvoyManifest {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(destPortalId, "destPortalId");
        members = List.copyOf(Objects.requireNonNull(members, "members"));
    }

    /** The transferring player's member, or null when the manifest carries no player. */
    public Member rider() {
        for (Member member : members) {
            if (member.rider()) {
                return member;
            }
        }
        return null;
    }

    public UUID playerId() {
        Member rider = rider();
        return rider == null ? null : rider.entityId();
    }

    public void write(DataOutputStream out) throws IOException {
        if (members.size() > MAX_MEMBERS) {
            throw new IOException("Convoy manifest too large: " + members.size() + " > " + MAX_MEMBERS);
        }
        writeUuid(out, groupId);
        writeUuid(out, destPortalId);
        out.writeInt(members.size());
        for (Member member : members) {
            writeUuid(out, member.entityId());
            WireCodec.writeByteArray(out, member.snapshot(), WireMessage.EntityTransfer.MAX_SNAPSHOT_BYTES);
            writeOptionalUuid(out, member.vehicleId());
            writeOptionalUuid(out, member.leashHolderId());
            member.traversive().write(out);
            out.writeBoolean(member.rider());
        }
    }

    public static ConvoyManifest read(DataInputStream in) throws IOException {
        UUID groupId = readUuid(in);
        UUID destPortalId = readUuid(in);
        int count = in.readInt();
        if (count < 0 || count > MAX_MEMBERS) {
            throw new IOException("Invalid convoy manifest size: " + count);
        }
        List<Member> members = new ArrayList<Member>(count);
        for (int index = 0; index < count; index++) {
            UUID entityId = readUuid(in);
            byte[] snapshot = WireCodec.readByteArray(in, WireMessage.EntityTransfer.MAX_SNAPSHOT_BYTES);
            UUID vehicleId = readOptionalUuid(in);
            UUID leashHolderId = readOptionalUuid(in);
            WireTraversive traversive = WireTraversive.read(in);
            boolean rider = in.readBoolean();
            members.add(new Member(entityId, snapshot, vehicleId, leashHolderId, traversive, rider));
        }
        return new ConvoyManifest(groupId, destPortalId, members);
    }

    private static void writeOptionalUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeBoolean(id != null);
        if (id != null) {
            writeUuid(out, id);
        }
    }

    private static UUID readOptionalUuid(DataInputStream in) throws IOException {
        return in.readBoolean() ? readUuid(in) : null;
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }
}
