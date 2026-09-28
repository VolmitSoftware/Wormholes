package art.arcane.wormholes.portal.vanilla;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DimensionalRouting {
    public static final double DEFAULT_SCALE = 1.0D;
    public static final double MAX_COORDINATE = 30_000_000.0D;
    public static final int BORDER_MARGIN = 16;

    private DimensionalRouting() {
    }

    public static double scaleOf(String worldKey, List<String> scales) {
        if (worldKey == null || scales == null) {
            return DEFAULT_SCALE;
        }
        for (String entry : scales) {
            if (entry == null) {
                continue;
            }
            int split = entry.lastIndexOf(':');
            if (split <= 0 || split == entry.length() - 1) {
                continue;
            }
            if (!entry.substring(0, split).trim().equalsIgnoreCase(worldKey)) {
                continue;
            }
            try {
                double scale = Double.parseDouble(entry.substring(split + 1).trim());
                if (scale > 0.0D && Double.isFinite(scale)) {
                    return scale;
                }
            } catch (NumberFormatException malformed) {
                return DEFAULT_SCALE;
            }
        }
        return DEFAULT_SCALE;
    }

    public static int[] map(int sourceX, int sourceZ, double sourceScale, double destinationScale) {
        double ratio = destinationScale <= 0.0D ? 1.0D : sourceScale / destinationScale;
        return new int[]{
            (int) Math.floor(sourceX * ratio),
            (int) Math.floor(sourceZ * ratio)
        };
    }

    public static int[] clampToBorder(int[] coordinates, double borderRadius) {
        double limit = Math.min(borderRadius <= 0.0D ? MAX_COORDINATE : borderRadius, MAX_COORDINATE) - BORDER_MARGIN;
        int bound = (int) Math.max(0.0D, limit);
        return new int[]{
            Math.max(-bound, Math.min(bound, coordinates[0])),
            Math.max(-bound, Math.min(bound, coordinates[1]))
        };
    }

    public static Optional<String> groupOf(String worldName, List<String> groups) {
        if (worldName == null || groups == null) {
            return Optional.empty();
        }
        for (String group : groups) {
            if (members(group).contains(worldName)) {
                return Optional.of(group);
            }
        }
        return Optional.empty();
    }

    public static boolean canPair(String a, String b, List<String> groups) {
        if (groups == null || groups.isEmpty()) {
            return true;
        }
        if (isDisabled(a, groups) || isDisabled(b, groups)) {
            return false;
        }
        Optional<String> groupA = groupOf(a, groups);
        Optional<String> groupB = groupOf(b, groups);
        if (groupA.isEmpty() && groupB.isEmpty()) {
            return true;
        }
        return groupA.isPresent() && groupA.equals(groupB);
    }

    public static boolean isDisabled(String worldName, List<String> groups) {
        if (worldName == null || groups == null) {
            return false;
        }
        for (String group : groups) {
            List<String> members = members(group);
            if (members.size() > 1 && members.stream().allMatch(worldName::equals)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> members(String group) {
        List<String> members = new ArrayList<>();
        if (group == null) {
            return members;
        }
        for (String member : group.split(",")) {
            String trimmed = member.trim();
            if (!trimmed.isEmpty()) {
                members.add(trimmed);
            }
        }
        return members;
    }
}
