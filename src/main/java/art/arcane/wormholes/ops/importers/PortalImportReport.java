package art.arcane.wormholes.ops.importers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What an import did, or would do: created portals, skips with reasons, unlinked names, and notes. */
public final class PortalImportReport {
    /** One portal the importer refused, and why. */
    public record Skip(String name, String reason) {
    }

    private final String importerId;
    private final boolean dryRun;
    private final List<String> created = new ArrayList<>();
    private final List<Skip> skipped = new ArrayList<>();
    private final List<String> unlinked = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    public PortalImportReport(String importerId, boolean dryRun) {
        this.importerId = importerId;
        this.dryRun = dryRun;
    }

    public String importerId() {
        return importerId;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public void created(String name) {
        created.add(name);
    }

    public void skipped(String name, String reason) {
        skipped.add(new Skip(name, reason));
    }

    /** A portal that came in but whose destination could not be resolved. */
    public void unlinked(String destinationName) {
        unlinked.add(destinationName);
    }

    public void note(String note) {
        notes.add(note);
    }

    public List<String> created() {
        return Collections.unmodifiableList(created);
    }

    public List<Skip> skipped() {
        return Collections.unmodifiableList(skipped);
    }

    public List<String> unlinked() {
        return Collections.unmodifiableList(unlinked);
    }

    public List<String> notes() {
        return Collections.unmodifiableList(notes);
    }

    public int createdCount() {
        return created.size();
    }

    public int skippedCount() {
        return skipped.size();
    }
}
