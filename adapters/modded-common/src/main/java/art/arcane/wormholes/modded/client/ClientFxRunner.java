package art.arcane.wormholes.modded.client;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.wormholes.portal.AmbientSparkCadence;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.wormholes.portal.effects.PortalAnimation;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

public final class ClientFxRunner {
    public static final int MAX_ANIMATIONS = 64;

    private final ClientSceneWorld world;
    private final Int2ObjectOpenHashMap<PortalFx> portals;
    private final List<PortalAnimation<Object>> animations;
    private final AnimationHost host;
    private int clientTick;
    private long fired;
    private boolean particlesActive = true;

    public ClientFxRunner(ClientSceneWorld world) {
        this.world = Objects.requireNonNull(world, "world");
        this.portals = new Int2ObjectOpenHashMap<>(8);
        this.animations = new ArrayList<>(4);
        this.host = new AnimationHost(world);
    }

    public void apply(ClientViewMessage.Fx fx, IntFunction<ClientPortal> lookup, boolean oneShots) {
        Objects.requireNonNull(fx, "fx");
        List<ClientViewMessage.FxEmitter> emitters = fx.emitters();
        List<ClientViewMessage.FxEmitter> continuous = new ArrayList<>(emitters.size());
        ClientPortal portal = null;
        boolean resolved = false;
        for (int index = 0; index < emitters.size(); index++) {
            ClientViewMessage.FxEmitter emitter = emitters.get(index);
            if (!ClientViewEmitters.oneShot(emitter)) {
                continuous.add(emitter);
                continue;
            }
            if (!oneShots && emitter.kind() != ClientViewMessage.FxKind.SOUND) {
                continue;
            }
            if (!resolved) {
                portal = lookup.apply(fx.portalKey());
                resolved = true;
            }
            fire(new Active(emitter), portal);
        }
        if (fx.portalKey() == ViewStreamLimits.WORLD_FX_KEY && continuous.isEmpty()) {
            return;
        }
        replace(fx.portalKey(), continuous);
    }

    public void tick(IntFunction<ClientPortal> lookup) {
        clientTick++;
        for (int index = animations.size() - 1; index >= 0; index--) {
            if (!animations.get(index).tick()) {
                animations.remove(index);
            }
        }
        ObjectIterator<Int2ObjectMap.Entry<PortalFx>> iterator = portals.int2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Int2ObjectMap.Entry<PortalFx> entry = iterator.next();
            ClientPortal portal = lookup.apply(entry.getIntKey());
            List<Active> active = entry.getValue().active;
            for (int index = 0; index < active.size(); index++) {
                Active emitter = active.get(index);
                int ticks = emitter.emitter.ticks();
                if (ticks <= 0 || Math.floorMod(clientTick + index, ticks) != 0) {
                    continue;
                }
                fire(emitter, portal);
            }
        }
    }

    public void drop(int portalKey) {
        portals.remove(portalKey);
    }

    public void clear() {
        portals.clear();
        clearAnimations();
    }

    public void particlesActive(boolean active) {
        particlesActive = active;
        if (!active) {
            clearAnimations();
        }
    }

    public int animations() {
        return animations.size();
    }

    public int emitters() {
        int count = 0;
        for (PortalFx fx : portals.values()) {
            count += fx.active.size();
        }
        return count;
    }

    public int emitters(int portalKey) {
        PortalFx fx = portals.get(portalKey);
        return fx == null ? 0 : fx.active.size();
    }

    public long fired() {
        return fired;
    }

    private void fire(Active active, ClientPortal portal) {
        ClientViewMessage.FxEmitter emitter = active.emitter;
        if (!particlesActive && emitter.kind() != ClientViewMessage.FxKind.SOUND) {
            if (emitter.kind() == ClientViewMessage.FxKind.SURFACE) {
                active.cursor++;
            }
            return;
        }
        switch (emitter.kind()) {
            case RIM_DUST -> {
                int count = Math.max(1, emitter.flags());
                for (int i = 0; i < count; i++) {
                    world.dust(emitter.x(), emitter.y(), emitter.z(), (int) emitter.paramA() & 0xFFFFFF, emitter.paramB());
                }
            }
            case SURFACE -> surface(active, portal);
            case SOUND -> world.sound(emitter.key(), emitter.x(), emitter.y(), emitter.z(), emitter.paramA(), emitter.paramB(),
                ClientViewEmitters.soundClass(emitter));
            case DOOR_ANIM -> world.particle(emitter.key(), emitter.x(), emitter.y(), emitter.z(), emitter.paramA(), emitter.paramB(),
                Math.max(1, emitter.flags()));
            case ANIMATION -> animate(emitter);
            case BURST -> world.burst(emitter.key(), emitter.x(), emitter.y(), emitter.z(), emitter.paramA(), emitter.paramB(),
                ClientViewEmitters.burstSpeed(emitter), Math.max(1, emitter.ticks()));
        }
        fired++;
    }

    private void clearAnimations() {
        for (int index = 0; index < animations.size(); index++) {
            animations.get(index).close();
        }
        animations.clear();
    }

    private void replace(int portalKey, List<ClientViewMessage.FxEmitter> emitters) {
        if (emitters.isEmpty()) {
            portals.remove(portalKey);
            return;
        }
        PortalFx previous = portals.get(portalKey);
        PortalFx next = new PortalFx(emitters.size());
        for (int index = 0; index < emitters.size(); index++) {
            ClientViewMessage.FxEmitter emitter = emitters.get(index);
            Active active = new Active(emitter);
            if (previous != null && index < previous.active.size() && previous.active.get(index).emitter.equals(emitter)) {
                active.cursor = previous.active.get(index).cursor;
            }
            next.active.add(active);
        }
        portals.put(portalKey, next);
    }

    private void animate(ClientViewMessage.FxEmitter emitter) {
        ClientViewEmitters.Animation animation = ClientViewEmitters.animation(emitter);
        if (animation == null || animations.size() >= MAX_ANIMATIONS) {
            return;
        }
        PortalAnimation<Object> running = new PortalAnimation<>(new PortalAnimation.Options(animation.mode(), animation.center(), animation.size(),
            animation.quality(), true, 0.0D, () -> true, () -> false, List.of()), host);
        if (running.tick()) {
            animations.add(running);
        }
    }

    private void surface(Active active, ClientPortal portal) {
        ClientViewMessage.FxEmitter emitter = active.emitter;
        boolean open = (emitter.flags() & ClientViewEmitters.SURFACE_OPEN_FLAG) != 0;
        int interval = (emitter.flags() >>> ClientViewEmitters.SURFACE_INTERVAL_SHIFT) & ClientViewEmitters.MAX_SURFACE_INTERVAL;
        int count = AmbientSparkCadence.burst(active.cursor++, interval, open);
        if (count <= 0) {
            return;
        }
        Vec3d cell = portal == null ? null : active.cell(portal.geometry());
        double x = cell == null ? emitter.x() : cell.x();
        double y = cell == null ? emitter.y() : cell.y();
        double z = cell == null ? emitter.z() : cell.z();
        world.particle(emitter.key(), x, y, z, emitter.paramA(), emitter.paramB(), count);
    }

    private static final class AnimationHost implements PortalAnimation.Host<Object> {
        private final ClientSceneWorld world;

        private AnimationHost(ClientSceneWorld world) {
            this.world = world;
        }

        @Override
        public void particle(PortalAnimation.ParticleEmission emission) {
            world.emission(emission);
        }

        @Override
        public void sound(PortalAnimation.SoundEmission sound) {
        }

        @Override
        public Object spawn(PortalAnimation.DisplaySpec display) {
            return display;
        }

        @Override
        public void transform(Object display, PortalAnimation.DisplaySpec transform) {
        }

        @Override
        public void remove(Object display) {
        }
    }

    private static final class PortalFx {
        private final List<Active> active;

        private PortalFx(int size) {
            this.active = new ArrayList<>(size);
        }
    }

    private static final class Active {
        private final ClientViewMessage.FxEmitter emitter;
        private long cursor;
        private ApertureDescriptor apertureOf;
        private ApertureCells aperture;

        private Active(ClientViewMessage.FxEmitter emitter) {
            this.emitter = emitter;
        }

        private Vec3d cell(ApertureDescriptor geometry) {
            if (geometry != apertureOf) {
                apertureOf = geometry;
                aperture = geometry.aperture();
            }
            return aperture.randomCellCentre();
        }
    }
}
