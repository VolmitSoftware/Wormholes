package art.arcane.optics.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class PlayerNames<O> {
    public static final String FLIP_NAME = "Dinnerbone";
    private static final String FLIP_NAME_ALT = "Grumm";
    private final Map<String, Integer> members = new HashMap<>(4);
    private final EntityOutput<O, ?, ?, ?, ?> output;
    private final String teamName;
    private boolean sent;

    public PlayerNames(EntityOutput<O, ?, ?, ?, ?> output, String teamName) {
        this.output = Objects.requireNonNull(output, "output");
        this.teamName = Objects.requireNonNull(teamName, "teamName");
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
