package art.arcane.optics.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class PlayerNames<O> {
    public static final String FLIP_NAME = "Dinnerbone";
    private static final String FLIP_NAME_ALT = "Grumm";
    private static final String NEUTRAL_PROFILE_NAME = "PortalPlayer";
    private static final AtomicInteger NEXT_NAME_TEAM_ID = new AtomicInteger();
    private final String teamName = "whpn" + Integer.toUnsignedString(NEXT_NAME_TEAM_ID.getAndIncrement(), 36);
    private final Map<String, Integer> members = new HashMap<>(4);
    private final EntityOutput<O, ?, ?, ?, ?> output;
    private boolean sent;

    public PlayerNames(EntityOutput<O, ?, ?, ?, ?> output) {
        this.output = output;
    }

    public static String projectedProfileName(String sourceName, UUID fakeUuid, boolean upsideDown) {
        if (upsideDown) {
            return isFlipName(sourceName) ? NEUTRAL_PROFILE_NAME : FLIP_NAME;
        }
        return syntheticProfileName(fakeUuid);
    }

    public static String syntheticProfileName(UUID fakeUuid) {
        String compact = fakeUuid.toString().replace("-", "");
        return "wh" + compact.substring(0, 14);
    }

    public static String playerLabelText(String name) {
        String safe = name == null || name.isBlank() ? NEUTRAL_PROFILE_NAME : name;
        if (safe.length() <= 16) {
            return safe;
        }
        return safe.substring(0, 16);
    }

    public static boolean isFlipName(String name) {
        return FLIP_NAME.equals(name) || FLIP_NAME_ALT.equals(name);
    }

    public static double labelY(double y, double height) {
        double safeHeight = Double.isFinite(height) ? Math.max(0.0D, height) : 0.0D;
        return y + safeHeight + 0.5D;
    }

    public void retain(O observer, String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        if (!sent) {
            output.team(observer, EntityOutput.TeamOp.CREATE, teamName, null);
            sent = true;
        }
        int references = members.getOrDefault(name, 0);
        members.put(name, references + 1);
        if (references == 0) {
            output.team(observer, EntityOutput.TeamOp.ADD, teamName, name);
        }
    }

    public void release(O observer, String name) {
        if (name == null) {
            return;
        }
        Integer references = members.get(name);
        if (references == null) {
            return;
        }
        if (references.intValue() > 1) {
            members.put(name, references.intValue() - 1);
            return;
        }
        members.remove(name);
        if (sent) {
            output.team(observer, EntityOutput.TeamOp.REMOVE, teamName, name);
        }
    }

    public void removeTeam(O observer) {
        if (sent) {
            output.team(observer, EntityOutput.TeamOp.REMOVE_TEAM, teamName, null);
        }
    }

    public void forget() {
        members.clear();
        sent = false;
    }

    public boolean hasTeam() {
        return sent;
    }
}
