package art.arcane.wormholes.ops.importers;

import java.util.UUID;

/** How an importer turns a parsed portal into a real one. Implementations touch the world; parsers do not. */
public interface PortalFactoryBridge {
    /** What building one portal did: the new portal's id, or why the site was refused. */
    record CreateResult(UUID portalId, String reason) {
        public static CreateResult created(UUID portalId) {
            return new CreateResult(portalId, "");
        }

        public static CreateResult refused(String reason) {
            return new CreateResult(null, reason);
        }

        public boolean ok() {
            return portalId != null;
        }
    }

    /** Builds the portal, or reports why it could not: missing world, occupied site, unusable aperture. */
    CreateResult create(ImportedPortal portal);

    /** Points an imported portal at another imported portal by name; false when the name is unknown. */
    boolean link(UUID source, String destinationName);
}
