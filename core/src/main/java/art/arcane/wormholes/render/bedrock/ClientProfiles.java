package art.arcane.wormholes.render.bedrock;

import art.arcane.wormholes.render.FidelitySettings;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

public final class ClientProfiles<P> {
    private final Options<P> options;
    private final Map<UUID, BedrockProfile> profiles = new ConcurrentHashMap<>();

    public ClientProfiles(Options<P> options) {
        this.options = options;
    }

    public BedrockProfile profile(P player) {
        if (player == null || !FidelitySettings.bedrockEnabled) {
            return BedrockProfile.JAVA;
        }
        return profiles.computeIfAbsent(options.identity().apply(player), ignored -> detect(player));
    }

    public void forget(UUID playerId) {
        if (playerId != null) { profiles.remove(playerId); }
    }

    public void clear() { profiles.clear(); }

    public int bedrockViewers() {
        int count = 0;
        for (BedrockProfile profile : profiles.values()) {
            if (profile.bedrock()) { count++; }
        }
        return count;
    }

    private BedrockProfile detect(P player) {
        boolean bedrock = options.detector().test(player);
        if (!bedrock) {
            String brand = options.brands().apply(player);
            bedrock = brand != null && brand.toLowerCase(Locale.ROOT).contains("geyser");
        }
        if (!bedrock) {
            bedrock = options.identity().apply(player).getMostSignificantBits() == 0L;
        }
        return bedrock ? BedrockProfile.forBedrock() : BedrockProfile.JAVA;
    }

    public record Options<P>(Predicate<P> detector, Function<P, String> brands, Function<P, UUID> identity) { }
}
