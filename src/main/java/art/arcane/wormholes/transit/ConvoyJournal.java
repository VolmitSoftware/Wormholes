package art.arcane.wormholes.transit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import art.arcane.volmlib.util.json.JSONArray;
import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.convoy.ConvoyLedger;
import art.arcane.wormholes.network.convoy.ConvoyTransferService;
import art.arcane.wormholes.util.VIO;

/**
 * {@code convoy/journal.json}: the source-side rigs still in flight, rewritten atomically on every ledger
 * change so a crash mid-transfer leaves a record of which entities were frozen and when.
 */
public final class ConvoyJournal implements ConvoyTransferService.Journal {
    public static final String FILE_NAME = "journal.json";

    private final Path file;

    public ConvoyJournal(Path folder) {
        this.file = folder.resolve(FILE_NAME);
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized void record(List<ConvoyLedger.Group> inFlight) {
        JSONArray groups = new JSONArray();
        for (ConvoyLedger.Group group : inFlight) {
            JSONObject entry = new JSONObject();
            entry.put("groupId", group.groupId().toString());
            entry.put("playerId", group.playerId() == null ? "" : group.playerId().toString());
            entry.put("peer", group.peerName() == null ? "" : group.peerName());
            entry.put("phase", group.phase().name());
            entry.put("openedAt", group.openedAtMillis());
            JSONArray members = new JSONArray();
            for (UUID member : group.members()) {
                members.put(member.toString());
            }
            entry.put("members", members);
            groups.put(entry);
        }
        JSONObject root = new JSONObject();
        root.put("groups", groups);
        try {
            VIO.writeAll(file.toFile(), root.toString(2));
        } catch (IOException failure) {
            Wormholes.w("[convoy] journal write failed: " + failure);
        }
    }

    /** Groups recorded by the previous run; an unreadable or missing journal reads as empty. */
    public synchronized List<ConvoyLedger.Group> load() {
        List<ConvoyLedger.Group> groups = new ArrayList<ConvoyLedger.Group>();
        File journal = file.toFile();
        if (!journal.isFile()) {
            return groups;
        }
        try {
            JSONObject root = new JSONObject(VIO.readAll(journal));
            JSONArray entries = root.optJSONArray("groups");
            if (entries == null) {
                return groups;
            }
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.getJSONObject(index);
                List<UUID> members = new ArrayList<UUID>();
                JSONArray memberIds = entry.optJSONArray("members");
                for (int memberIndex = 0; memberIds != null && memberIndex < memberIds.length(); memberIndex++) {
                    members.add(UUID.fromString(memberIds.getString(memberIndex)));
                }
                String playerId = entry.optString("playerId", "");
                groups.add(new ConvoyLedger.Group(
                    UUID.fromString(entry.getString("groupId")),
                    playerId.isEmpty() ? null : UUID.fromString(playerId),
                    entry.optString("peer", ""),
                    members,
                    entry.optLong("openedAt", 0L),
                    ConvoyLedger.Phase.valueOf(entry.optString("phase", ConvoyLedger.Phase.OPEN.name())),
                    ""));
            }
        } catch (IOException | RuntimeException failure) {
            Wormholes.w("[convoy] journal unreadable, ignoring: " + failure);
        }
        return groups;
    }

    public synchronized void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            Wormholes.w("[convoy] journal delete failed: " + failure);
        }
    }

    /** Groups whose transfer window has elapsed; their frozen members should be restored. */
    public static List<ConvoyLedger.Group> stale(List<ConvoyLedger.Group> groups, long nowMillis, long timeoutMillis) {
        List<ConvoyLedger.Group> stale = new ArrayList<ConvoyLedger.Group>();
        for (ConvoyLedger.Group group : groups) {
            if (nowMillis - group.openedAtMillis() >= timeoutMillis) {
                stale.add(group);
            }
        }
        return stale;
    }
}
