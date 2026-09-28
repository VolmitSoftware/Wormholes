package art.arcane.wormholes.access;

import java.util.Collection;

public final class PortalOwnershipLimits {
    public static final String LIMIT_NODE_PREFIX = "wormholes.limit.";

    private PortalOwnershipLimits() {
    }

    public static int maximum(boolean administrator, int configuredDefault, Collection<String> grantedNodes) {
        if (administrator) {
            return 0;
        }
        int fromNodes = highestLimitNode(grantedNodes);
        return fromNodes >= 0 ? fromNodes : Math.max(0, configuredDefault);
    }

    /** -1 when the player holds no usable {@code wormholes.limit.<n>} node. */
    public static int highestLimitNode(Collection<String> nodes) {
        int highest = -1;
        for (String node : nodes) {
            if (node == null || !node.startsWith(LIMIT_NODE_PREFIX)) {
                continue;
            }
            String tail = node.substring(LIMIT_NODE_PREFIX.length());
            if (tail.isEmpty() || tail.length() > 9) {
                continue;
            }
            int value = 0;
            boolean numeric = true;
            for (int index = 0; index < tail.length(); index++) {
                char digit = tail.charAt(index);
                if (digit < '0' || digit > '9') {
                    numeric = false;
                    break;
                }
                value = value * 10 + (digit - '0');
            }
            if (numeric && value > highest) {
                highest = value;
            }
        }
        return highest;
    }

}
