package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.rules.Effect;
import art.arcane.wormholes.rules.RuleEffects;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class MinecraftRuleEffects {
    private static final Logger LOGGER = LoggerFactory.getLogger("Wormholes");

    private MinecraftRuleEffects() { }

    static void run(WormholesModRuntime runtime, Entity traveler, MinecraftPortal destination, List<Effect> effects) {
        RuleEffects.run(new Host(runtime, traveler, destination), effects);
    }

    private record Host(WormholesModRuntime runtime, Entity traveler, MinecraftPortal destination) implements RuleEffects.Host {
        @Override
        public UUID travelerId() { return traveler.getUUID(); }
        @Override
        public UUID destinationId() { return destination.getId(); }
        @Override
        public long nowMillis() { return System.currentTimeMillis(); }
        @Override
        public boolean isPlayer() { return traveler instanceof ServerPlayer; }
        @Override
        public String playerName() { return traveler.getName().getString(); }
        @Override
        public void scaleVelocity(double multiplier) { traveler.setDeltaMovement(traveler.getDeltaMovement().scale(multiplier)); }
        @Override
        public void potion(Effect.Potion effect) {
            Identifier id = Identifier.tryParse(effect.type().toLowerCase(Locale.ROOT));
            MobEffect type = id == null ? null : BuiltInRegistries.MOB_EFFECT.getOptional(id).orElse(null);
            if (type == null) {
                return;
            }
            Holder<MobEffect> holder = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(type);
            ServerPlayer player = (ServerPlayer) traveler;
            if (effect.clear()) {
                player.removeEffect(holder);
            } else {
                player.addEffect(new MobEffectInstance(holder, effect.ticks(), effect.amplifier()));
            }
        }
        @Override
        public void message(String literal, TextKey key) {
            ServerPlayer player = (ServerPlayer) traveler;
            player.sendSystemMessage(key == null ? MinecraftMenuText.format(literal, Map.of())
                : MinecraftMenuText.text(player, key, Map.of()));
        }
        @Override
        public void sound(Effect.Sound effect) {
            Identifier id = Identifier.tryParse(effect.key());
            if (id != null) {
                SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(id).orElseGet(() -> SoundEvent.createVariableRangeEvent(id));
                traveler.level().playSound(null, traveler.getX(), traveler.getY(), traveler.getZ(), sound,
                    SoundSource.PLAYERS, effect.volume(), effect.pitch());
            }
        }
        @Override
        public boolean consoleCommandsEnabled() {
            return runtime.configuration().settings().getRules().commandEffectsConsoleAllowed;
        }
        @Override
        public boolean authorMayRunConsoleCommands(UUID authorId) {
            if (authorId == null) {
                return false;
            }
            ServerPlayer author = runtime.server().getPlayerList().getPlayer(authorId);
            return author == null ? runtime.server().getPlayerList().isOp(new NameAndId(authorId, ""))
                : runtime.access().administrator(author);
        }
        @Override
        public void consoleCommandRefused(UUID authorId) {
            LOGGER.warn("Refused traversal rule console command: author {} is no longer an administrator", authorId);
        }
        @Override
        public void command(String line, boolean console) {
            runtime.server().getCommands().performPrefixedCommand(console ? runtime.server().createCommandSourceStack()
                : ((ServerPlayer) traveler).createCommandSourceStack(), line);
        }
    }
}
