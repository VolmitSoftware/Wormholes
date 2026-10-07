package art.arcane.optics.fidelity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import java.util.function.Function;

import java.util.function.Supplier;

import art.arcane.optics.claim.ProjectionOutput;
import art.arcane.optics.internal.fidelity.SoundAttenuation;

/**
 * Routes destination-side sound events to the observers of every portal that looks at that place,
 * positioned at the local aperture centre and attenuated by the event's distance from the far side.
 * Apertures are registered by the projectors each pass and expire when a portal stops projecting.
 * Thread-safe: events arrive on region threads, ambient ticks on the projection tick.
 */
public final class AcousticsBridge<O> {
    public static final long APERTURE_TTL_MILLIS = 10_000L;
    public static final int AMBIENT_INTERVAL_TICKS = 80;
    static final long AMBIENT_INTERVAL_MILLIS = AMBIENT_INTERVAL_TICKS * 50L;
    static final float AMBIENT_VOLUME = 0.25F;
    private static final long RATE_WINDOW_MILLIS = 1_000L;

    public record SoundEvent(UUID worldId, double x, double y, double z, String soundKey, float volume, float pitch,
                             AcousticsProfile.SoundClass soundClass) {
    }

    public record Playback(String soundKey, AcousticsProfile.SoundClass soundClass, double x, double y, double z, float volume, float pitch) {
    }

    public record Options<O>(ProjectionOutput<O> output, Function<O, UUID> observerId, Supplier<FidelityOptions> fidelity) {
    }

    public enum Environment {
        NORMAL,
        NETHER,
        THE_END,
        CUSTOM
    }

    private static final class Aperture {
        private final UUID destinationWorldId;
        private final String peer;
        private final UUID remotePortalId;
        private final double destinationX;
        private final double destinationY;
        private final double destinationZ;
        private final double apertureX;
        private final double apertureY;
        private final double apertureZ;
        private final AcousticsProfile profile;
        private final Environment environment;
        private final boolean storm;
        private final long seenMillis;
        private volatile long lastAmbientMillis;

        private Aperture(UUID destinationWorldId, String peer, UUID remotePortalId,
                         double destinationX, double destinationY, double destinationZ,
                         double apertureX, double apertureY, double apertureZ,
                         AcousticsProfile profile, Environment environment, boolean storm,
                         long seenMillis, long lastAmbientMillis) {
            this.destinationWorldId = destinationWorldId;
            this.peer = peer;
            this.remotePortalId = remotePortalId;
            this.destinationX = destinationX;
            this.destinationY = destinationY;
            this.destinationZ = destinationZ;
            this.apertureX = apertureX;
            this.apertureY = apertureY;
            this.apertureZ = apertureZ;
            this.profile = profile;
            this.environment = environment;
            this.storm = storm;
            this.seenMillis = seenMillis;
            this.lastAmbientMillis = lastAmbientMillis;
        }
    }

    private static final class RateState {
        private long windowStartMillis;
        private int count;
    }

    private final ProjectionOutput<O> output;
    private final Function<O, UUID> observerId;
    private final Supplier<FidelityOptions> fidelity;
    private final Map<UUID, Aperture> apertures;
    private final Map<UUID, RateState> rates;

    public AcousticsBridge(Options<O> options) {
        this.output = options.output();
        this.observerId = options.observerId();
        this.fidelity = options.fidelity();
        this.apertures = new ConcurrentHashMap<UUID, Aperture>();
        this.rates = new ConcurrentHashMap<UUID, RateState>();
    }

    public void noteDestination(UUID portalId, UUID destinationWorldId,
                                double destinationX, double destinationY, double destinationZ,
                                double apertureX, double apertureY, double apertureZ,
                                AcousticsProfile profile, Environment environment, boolean storm, long nowMillis) {
        Aperture previous = apertures.get(portalId);
        apertures.put(portalId, new Aperture(destinationWorldId, null, null, destinationX, destinationY, destinationZ,
            apertureX, apertureY, apertureZ, profile, environment, storm, nowMillis,
            previous == null ? -AMBIENT_INTERVAL_MILLIS : previous.lastAmbientMillis));
    }

    public void noteRemoteDestination(UUID portalId, String peer, UUID remotePortalId,
                                      double destinationX, double destinationY, double destinationZ,
                                      double apertureX, double apertureY, double apertureZ,
                                      AcousticsProfile profile, long nowMillis) {
        Aperture previous = apertures.get(portalId);
        apertures.put(portalId, new Aperture(null, peer, remotePortalId, destinationX, destinationY, destinationZ,
            apertureX, apertureY, apertureZ, profile, null, false, nowMillis,
            previous == null ? -AMBIENT_INTERVAL_MILLIS : previous.lastAmbientMillis));
    }

    public void forgetPortal(UUID portalId) {
        apertures.remove(portalId);
    }

    public boolean hasDestinationWorld(UUID worldId) {
        if (worldId == null) {
            return false;
        }
        for (Aperture aperture : apertures.values()) {
            if (worldId.equals(aperture.destinationWorldId)) {
                return true;
            }
        }
        return false;
    }

    public int apertureCount() {
        return apertures.size();
    }

    /** Relays a local destination event; returns the number of sounds played. */
    public int onEvent(SoundEvent event, long nowMillis) {
        if (event == null || event.worldId() == null) {
            return 0;
        }
        int played = 0;
        for (Map.Entry<UUID, Aperture> entry : apertures.entrySet()) {
            Aperture aperture = entry.getValue();
            if (!event.worldId().equals(aperture.destinationWorldId)) {
                continue;
            }
            played += relay(entry.getKey(), aperture, event.x(), event.y(), event.z(), event.soundKey(),
                event.volume(), event.pitch(), event.soundClass(), nowMillis);
        }
        return played;
    }

    /** Relays a sound the destination server forwarded for one of its portals we are linked to. */
    public int onRemoteSound(String peer, UUID remotePortalId, double x, double y, double z, String soundKey,
                             float volume, float pitch, AcousticsProfile.SoundClass soundClass, long nowMillis) {
        if (peer == null || remotePortalId == null) {
            return 0;
        }
        int played = 0;
        for (Map.Entry<UUID, Aperture> entry : apertures.entrySet()) {
            Aperture aperture = entry.getValue();
            if (!peer.equals(aperture.peer) || !remotePortalId.equals(aperture.remotePortalId)) {
                continue;
            }
            played += relay(entry.getKey(), aperture, x, y, z, soundKey, volume, pitch, soundClass, nowMillis);
        }
        return played;
    }

    /** Plays the dimension bed for admitted portals every four seconds and expires stale apertures. */
    public int tickAmbient(long nowMillis) {
        int played = 0;
        for (Map.Entry<UUID, Aperture> entry : apertures.entrySet()) {
            Aperture aperture = entry.getValue();
            if (nowMillis - aperture.seenMillis > APERTURE_TTL_MILLIS) {
                apertures.remove(entry.getKey(), aperture);
                continue;
            }
            if (!aperture.profile.admitsAmbient()
                || nowMillis - aperture.lastAmbientMillis < AMBIENT_INTERVAL_MILLIS) {
                continue;
            }
            String key = ambientKey(aperture.environment, aperture.storm);
            aperture.lastAmbientMillis = nowMillis;
            if (key == null) {
                continue;
            }
            for (O observer : output.observersOf(entry.getKey())) {
                if (output.clientAmbient(observer) || !admit(observer, nowMillis)) {
                    continue;
                }
                output.sound(observer, new Playback(key, AcousticsProfile.SoundClass.AMBIENT,
                    aperture.apertureX, aperture.apertureY, aperture.apertureZ, AMBIENT_VOLUME, 1.0F));
                played++;
            }
        }
        return played;
    }

    public Playback ambientBed(UUID portalId) {
        Aperture aperture = portalId == null ? null : apertures.get(portalId);
        if (aperture == null || !aperture.profile.admitsAmbient()) {
            return null;
        }
        String key = ambientKey(aperture.environment, aperture.storm);
        return key == null ? null : new Playback(key, AcousticsProfile.SoundClass.AMBIENT, aperture.apertureX, aperture.apertureY,
            aperture.apertureZ, AMBIENT_VOLUME, 1.0F);
    }

    static String ambientKey(Environment environment, boolean storm) {
        if (environment == null) {
            return storm ? "minecraft:weather.rain" : null;
        }
        return switch (environment) {
            case NETHER -> "minecraft:ambient.nether_wastes.loop";
            case THE_END -> "minecraft:ambient.cave";
            default -> storm ? "minecraft:weather.rain" : null;
        };
    }

    private int relay(UUID portalId, Aperture aperture, double x, double y, double z, String soundKey,
                      float volume, float pitch, AcousticsProfile.SoundClass soundClass, long nowMillis) {
        if (!aperture.profile.admits(soundClass)) {
            return 0;
        }
        double radius = fidelity.get().acousticsRadius();
        double dx = x - aperture.destinationX;
        double dy = y - aperture.destinationY;
        double dz = z - aperture.destinationZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > radius) {
            return 0;
        }
        float attenuatedVolume = SoundAttenuation.volume(volume, distance, radius);
        float attenuatedPitch = SoundAttenuation.pitch(pitch, distance, radius);
        int played = 0;
        for (O observer : output.observersOf(portalId)) {
            if (observer == null || !admit(observer, nowMillis)) {
                continue;
            }
            output.sound(observer, new Playback(soundKey, soundClass, aperture.apertureX, aperture.apertureY, aperture.apertureZ,
                attenuatedVolume, attenuatedPitch));
            played++;
        }
        return played;
    }

    private boolean admit(O observer, long nowMillis) {
        int cap = fidelity.get().acousticsRateCapPerObserver();
        if (cap <= 0) {
            return false;
        }
        RateState state = rates.computeIfAbsent(observerId.apply(observer), ignored -> new RateState());
        synchronized (state) {
            if (nowMillis - state.windowStartMillis >= RATE_WINDOW_MILLIS) {
                state.windowStartMillis = nowMillis;
                state.count = 0;
            }
            if (state.count >= cap) {
                return false;
            }
            state.count++;
            return true;
        }
    }

    public void forgetObserver(UUID observerId) {
        rates.remove(observerId);
    }

    public void clear() {
        apertures.clear();
        rates.clear();
    }
}
