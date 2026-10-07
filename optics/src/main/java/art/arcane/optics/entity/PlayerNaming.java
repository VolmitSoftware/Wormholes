package art.arcane.optics.entity;

import java.util.Objects;
import java.util.UUID;

public final class PlayerNaming {
    public static final int MAX_NAME_LENGTH = 16;

    private final String neutralName;
    private final String profilePrefix;

    public PlayerNaming(String neutralName, String profilePrefix) {
        this.neutralName = Objects.requireNonNull(neutralName, "neutralName");
        this.profilePrefix = Objects.requireNonNull(profilePrefix, "profilePrefix");
        if (neutralName.isBlank() || neutralName.length() > MAX_NAME_LENGTH || profilePrefix.length() >= MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("player naming needs a 1 to 16 character neutral name and a prefix shorter than 16");
        }
    }

    public String projectedProfileName(String sourceName, UUID fakeUuid, boolean upsideDown) {
        if (upsideDown) {
            return PlayerNames.isFlipName(sourceName) ? neutralName : PlayerNames.FLIP_NAME;
        }
        return syntheticProfileName(fakeUuid);
    }

    public String syntheticProfileName(UUID fakeUuid) {
        return profilePrefix + fakeUuid.toString().replace("-", "").substring(0, MAX_NAME_LENGTH - profilePrefix.length());
    }

    public String labelText(String name) {
        String safe = name == null || name.isBlank() ? neutralName : name;
        return safe.length() <= MAX_NAME_LENGTH ? safe : safe.substring(0, MAX_NAME_LENGTH);
    }
}
