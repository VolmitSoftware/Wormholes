package art.arcane.wormholes.ops.importers;

import java.nio.file.Path;

/** Reads another portal plugin's files and reports what it would build. */
public interface PortalImporter {
    String id();

    /** True when this plugin's data is present under the server root. */
    boolean detect(Path serverRoot);

    /** Parses the source. With {@code dryRun} the factory is never called. */
    PortalImportReport importFrom(Path serverRoot, boolean dryRun, PortalFactoryBridge factory);
}
