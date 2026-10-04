package art.arcane.wormholes.modded;

import art.arcane.wormholes.chunk.presend.ChunkPreSendTicket;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.TransitConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.portal.PortalCrossing;
import art.arcane.wormholes.transit.AdaptiveArrivalMask;
import art.arcane.wormholes.transit.MomentumTransform;
import art.arcane.wormholes.transit.TransitionProfile;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

public final class MinecraftTransit {
    private MinecraftTransit() { }

    public static boolean depart(WormholesModRuntime runtime, MinecraftPortal portal, Entity traveler, PortalCrossing crossing) {
        boolean bounce = Boolean.TRUE.equals(portal.setting("transit.bounce"));
        boolean membrane = Boolean.TRUE.equals(portal.setting("transit.membrane")) && !crossing.frontSide();
        if (!bounce && !membrane) {
            return true;
        }
        GeometryVector normal = new GeometryVector(crossing.frame().getNormal().x(), crossing.frame().getNormal().y(), crossing.frame().getNormal().z());
        GeometryVector rejected = bounce
            ? MomentumTransform.reflect(crossing.velocity(), normal, runtime.configuration().settings().getMain().portalPushbackMultiplier)
            : normal.multiply(3.0D * runtime.configuration().settings().getMain().portalPushbackMultiplier
                * runtime.rules().document(portal).profile().pushbackScale());
        Vec3 velocity = new Vec3(rejected.x(), rejected.y(), rejected.z());
        traveler.setDeltaMovement(velocity);
        if (traveler instanceof ServerPlayer player) {
            player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), velocity));
            player.sendSystemMessage(MinecraftMenuText.text(player, bounce ? TransitMessages.BOUNCED : TransitMessages.DENIED_MEMBRANE,
                Map.of("portal", portal.getName())), true);
        }
        return false;
    }

    public static void arrived(WormholesModRuntime runtime, MinecraftPortal source, ServerPlayer player,
                               boolean reloadExpected, ChunkPreSendTicket<ServerLevel, ServerPlayer> ticket, boolean seamless) {
        MainConfig main = runtime.configuration().settings().getMain();
        if (!reloadExpected || !main.arrivalTransitionMask || seamless) {
            return;
        }
        TransitConfig transit = runtime.configuration().settings().getTransit();
        Object encoded = source.setting("transit.profile");
        TransitionProfile profile = TransitionProfile.decode(encoded instanceof String text ? text : "");
        int ticks = profile.overridesMask() ? profile.maskOverrideTicks() : transit.arrivalMaskAdaptive
            ? AdaptiveArrivalMask.maskTicks(true, ticket == null ? null : ticket.outcome(), ticket == null ? 0 : ticket.sentChunks(),
                ticket == null ? 0 : ticket.plannedChunks(), transit.arrivalMaskMinTicks, main.arrivalTransitionMaskTicks)
            : main.arrivalTransitionMaskTicks;
        if (ticks > 0) {
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, ticks, 0, false, false, false));
        }
    }
}
