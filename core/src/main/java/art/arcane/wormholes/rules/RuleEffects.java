package art.arcane.wormholes.rules;

import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.localization.RulesMessages;

import java.util.List;
import java.util.UUID;

public final class RuleEffects {
    private RuleEffects() {
    }

    public static void run(Host host, List<Effect> effects) {
        for (Effect effect : effects) {
            switch (effect) {
                case Effect.Velocity value -> host.scaleVelocity(value.multiplier());
                case Effect.Potion value -> {
                    if (host.isPlayer()) {
                        host.potion(value);
                    }
                }
                case Effect.Command value -> command(host, value);
                case Effect.Message value -> {
                    if (host.isPlayer() && !value.message().isEmpty()) {
                        host.message(value.message(), catalogKey(value.message()));
                    }
                }
                case Effect.Sound value -> {
                    if (!value.key().isEmpty()) {
                        host.sound(value);
                    }
                }
                case Effect.StampCooldown value -> PortalCooldowns.stamp(host.travelerId(), host.destinationId(),
                    value.group(), value.millis(), host.nowMillis());
            }
        }
    }

    private static void command(Host host, Effect.Command effect) {
        if (!host.isPlayer() || effect.line().isEmpty()) {
            return;
        }
        String line = effect.line().replace("{player}", host.playerName());
        if (!effect.asConsole()) {
            host.command(line, false);
            return;
        }
        if (!host.consoleCommandsEnabled() || !host.authorMayRunConsoleCommands(effect.authorId())) {
            host.consoleCommandRefused(effect.authorId());
            return;
        }
        host.command(line, true);
    }

    private static TextKey catalogKey(String message) {
        for (MessageKey key : RulesMessages.keys()) {
            if (key instanceof TextKey text && text.id().equals(message) && text.placeholders().isEmpty()) {
                return text;
            }
        }
        return null;
    }

    public interface Host {
        UUID travelerId();
        UUID destinationId();
        long nowMillis();
        boolean isPlayer();
        String playerName();
        void scaleVelocity(double multiplier);
        void potion(Effect.Potion effect);
        void message(String literal, TextKey key);
        void sound(Effect.Sound effect);
        boolean consoleCommandsEnabled();
        boolean authorMayRunConsoleCommands(UUID authorId);
        void consoleCommandRefused(UUID authorId);
        void command(String line, boolean console);
    }
}
