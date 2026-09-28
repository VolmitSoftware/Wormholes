package art.arcane.wormholes.network.mesh;

import java.util.UUID;

/**
 * One target of a destination policy: a server plus either a portal id or a tag (the destination
 * portal's name on that server, matched case-insensitively) and a weight for the weighted strategies.
 * Text form is {@code server:portalId|tag[:weight]}.
 */
public record DestinationCandidate(String server, UUID portalId, String tag, int weight) {
    public DestinationCandidate {
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("candidate server is required");
        }
        if ((portalId == null) == (tag == null || tag.isBlank())) {
            throw new IllegalArgumentException("candidate needs exactly one of portal id or tag");
        }
        if (weight < 1) {
            throw new IllegalArgumentException("candidate weight must be at least 1");
        }
    }

    public static DestinationCandidate parse(String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.trim().split(":");
        if (parts.length < 2 || parts.length > 3 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        int weight = 1;
        if (parts.length == 3) {
            try {
                weight = Integer.parseInt(parts[2].trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (weight < 1) {
                return null;
            }
        }
        UUID portalId = null;
        String tag = null;
        try {
            portalId = UUID.fromString(parts[1].trim());
        } catch (IllegalArgumentException notAnId) {
            tag = parts[1].trim();
        }
        return new DestinationCandidate(parts[0].trim(), portalId, tag, weight);
    }

    public String target() {
        return portalId != null ? portalId.toString() : tag;
    }

    public String text() {
        return server + ':' + target() + ':' + weight;
    }
}
