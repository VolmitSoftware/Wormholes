package art.arcane.wormholes.portal;

import java.io.File;
import java.util.Objects;
import java.util.UUID;

public record PortalSaveSnapshot(
	File file,
	String encoded,
	long generation,
	UUID portalId,
	String portalName
)
{
	public PortalSaveSnapshot
	{
		Objects.requireNonNull(file, "file");
		Objects.requireNonNull(encoded, "encoded");
		Objects.requireNonNull(portalId, "portalId");
		Objects.requireNonNull(portalName, "portalName");
	}
}
