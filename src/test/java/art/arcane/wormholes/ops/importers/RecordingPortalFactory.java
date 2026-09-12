package art.arcane.wormholes.ops.importers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Records what an importer asked for instead of touching a world. */
final class RecordingPortalFactory implements PortalFactoryBridge {
    private final List<ImportedPortal> created = new ArrayList<>();
    private final Map<UUID, String> links = new LinkedHashMap<>();
    private final List<String> unusableWorlds = new ArrayList<>();
    private final List<String> occupiedSites = new ArrayList<>();
    private final List<String> knownDestinations = new ArrayList<>();

    RecordingPortalFactory withUnusableWorld(String world) {
        unusableWorlds.add(world);
        return this;
    }

    /** A site an earlier import already built on, so a second run reports it instead of duplicating. */
    RecordingPortalFactory withOccupiedSite(String name) {
        occupiedSites.add(name);
        return this;
    }

    RecordingPortalFactory withKnownDestination(String name) {
        knownDestinations.add(name);
        return this;
    }

    List<ImportedPortal> created() {
        return created;
    }

    Map<UUID, String> links() {
        return links;
    }

    @Override
    public CreateResult create(ImportedPortal portal) {
        if (unusableWorlds.contains(portal.worldName())) {
            return CreateResult.refused("world " + portal.worldName() + " is not loaded");
        }
        if (occupiedSites.contains(portal.name())) {
            return CreateResult.refused("a portal already exists here: " + portal.name());
        }
        created.add(portal);
        return CreateResult.created(UUID.nameUUIDFromBytes(portal.name().getBytes()));
    }

    @Override
    public boolean link(UUID source, String destinationName) {
        if (!knownDestinations.contains(destinationName)) {
            return false;
        }
        links.put(source, destinationName);
        return true;
    }
}
