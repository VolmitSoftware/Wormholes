package art.arcane.optics.stream;

import java.util.UUID;

public record EntityFrameTarget(UUID portalId, int portalKey, boolean full, boolean hideObserver) {
}
