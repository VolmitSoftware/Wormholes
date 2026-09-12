package art.arcane.wormholes.render;

import java.util.List;

import org.bukkit.entity.Player;

import art.arcane.wormholes.ProjectionManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.hook.WormholesRegistrar;
import art.arcane.wormholes.hook.WormholesSubsystem;
import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.WireMessageType;
import art.arcane.wormholes.render.acoustics.AcousticsBridge;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.acoustics.SoundEventSource;
import art.arcane.wormholes.render.acoustics.SoundPacketSink;
import art.arcane.wormholes.render.bedrock.ClientProfileService;

/** Lifecycle entry point for the view lane: per-portal fidelity extension, menu, channels and wire handlers. */
public final class FidelitySubsystem implements WormholesSubsystem {
    private static volatile AcousticsBridge acoustics;

    private SoundEventSource soundSource;

    @Override
    public String id() {
        return "fidelity";
    }

    public static AcousticsBridge acoustics() {
        return acoustics;
    }

    @Override
    public void register(WormholesRegistrar registrar) {
        registrar.portalExtension(new FidelityExtensionFactory());
        registrar.portalMenuEntry(new FidelityMenuEntry());
        registrar.wireHandler(WireMessageType.VIEW_WEATHER, (peerName, message) -> {
            if (!(message instanceof WireMessage.ViewWeather weather)) {
                return false;
            }
            if (Wormholes.remoteViewCache != null) {
                Wormholes.remoteViewCache.applyWeather(peerName, weather.portalId(), weather.storm(), weather.thunder());
            }
            return true;
        });
        registrar.wireHandler(WireMessageType.VIEW_SOUND, (peerName, message) -> {
            if (!(message instanceof WireMessage.ViewSound sound)) {
                return false;
            }
            AcousticsBridge bridge = acoustics;
            if (bridge != null) {
                AcousticsProfile.SoundClass[] classes = AcousticsProfile.SoundClass.values();
                int ordinal = sound.soundClass();
                AcousticsProfile.SoundClass soundClass = ordinal >= 0 && ordinal < classes.length ? classes[ordinal] : AcousticsProfile.SoundClass.WORLD;
                bridge.onRemoteSound(peerName, sound.portalId(), sound.x(), sound.y(), sound.z(), sound.soundKey(),
                    sound.volume(), sound.pitch(), soundClass, System.currentTimeMillis());
            }
            return true;
        });
    }

    @Override
    public void start(Wormholes plugin) {
        FidelitySettings.refresh(Wormholes.settings);
        ClientProfileService.install(ClientProfileService.detectFloodgateAndBrand(plugin));
        acoustics = new AcousticsBridge(new SoundPacketSink(), FidelitySubsystem::observersOf);
        soundSource = new SoundEventSource(FidelitySubsystem::acoustics);
        plugin.registerListener(soundSource);
    }

    @Override
    public void stop() {
        Wormholes plugin = Wormholes.instance;
        if (plugin != null && soundSource != null) {
            plugin.unregisterListener(soundSource);
        }
        soundSource = null;
        ClientProfileService.install(null);
        AcousticsBridge bridge = acoustics;
        acoustics = null;
        if (bridge != null) {
            bridge.clear();
        }
    }

    @Override
    public void onSettingsReloaded(WormholesSettings settings) {
        FidelitySettings.refresh(settings);
        ClientProfileService.forgetAll();
        ProjectionManager projection = Wormholes.projectionManager;
        if (projection != null) {
            projection.onFidelitySettingsReloaded();
        }
    }

    private static List<Player> observersOf(java.util.UUID portalId) {
        ProjectionManager projection = Wormholes.projectionManager;
        return projection == null ? List.of() : projection.observersOf(portalId);
    }
}
