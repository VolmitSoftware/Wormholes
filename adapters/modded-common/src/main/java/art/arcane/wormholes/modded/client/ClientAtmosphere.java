package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.render.client.ClientViewSweep;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.Objects;
import java.util.Random;
import java.util.function.IntFunction;

public final class ClientAtmosphere {
    public static final String RAIN_PARTICLE = "minecraft:rain";
    public static final int WEATHER_BURST_TICKS = 5;
    public static final float RAIN_THRESHOLD = 0.2F;
    private static final int RAIN_PARTICLES = 12;
    private static final int STORM_PARTICLES = 24;
    private static final int SAMPLE_ATTEMPTS = 4;
    private static final double LATERAL_MARGIN = 1.5D;

    private final ClientSceneWorld world;
    private final Int2ObjectOpenHashMap<Received> portals;
    private final Random random;
    private double dominanceBlocks;
    private int dominant;
    private Saved saved;
    private int clientTick;
    private long weatherParticles;

    public ClientAtmosphere(ClientSceneWorld world, double dominanceBlocks) {
        this.world = Objects.requireNonNull(world, "world");
        this.portals = new Int2ObjectOpenHashMap<>(4);
        this.random = new Random();
        this.dominanceBlocks = dominanceBlocks;
    }

    public boolean apply(ClientViewMessage.Atmosphere atmosphere) {
        Objects.requireNonNull(atmosphere, "atmosphere");
        int previous = skyDarken(atmosphere.portalKey());
        if ((atmosphere.flags() & ClientViewMessage.Atmosphere.FLAG_RESTORE) != 0) {
            drop(atmosphere.portalKey());
            return previous != 0;
        }
        portals.put(atmosphere.portalKey(), new Received(atmosphere, world.gameTime()));
        return previous != atmosphere.skyDarken();
    }

    public int skyDarken(int portalKey) {
        Received received = portals.get(portalKey);
        return received == null ? 0 : received.atmosphere.skyDarken();
    }

    public void tick(double eyeX, double eyeY, double eyeZ, IntFunction<ClientPortal> lookup) {
        clientTick++;
        observeLocalWeather();
        int next = 0;
        double best = Double.MAX_VALUE;
        ObjectIterator<Int2ObjectMap.Entry<Received>> iterator = portals.int2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Int2ObjectMap.Entry<Received> entry = iterator.next();
            ClientPortal portal = lookup.apply(entry.getIntKey());
            if (portal == null || !portal.ready()) {
                continue;
            }
            ClientViewMessage.Atmosphere atmosphere = entry.getValue().atmosphere;
            weather(portal, atmosphere);
            if ((atmosphere.flags() & (ClientViewMessage.Atmosphere.FLAG_TIME | ClientViewMessage.Atmosphere.FLAG_WEATHER)) == 0) {
                continue;
            }
            double distance = Math.abs(portal.sweep().eyeDot());
            if (distance <= dominanceBlocks && distance < best && inside(portal, eyeX, eyeY, eyeZ)) {
                best = distance;
                next = entry.getIntKey();
            }
        }
        if (next != dominant) {
            restore();
            dominant = next;
            if (dominant != 0) {
                saved = new Saved(world.rain(), world.thunder(), world.hasClock() ? world.clock() - world.gameTime() : 0L);
            }
        }
        if (dominant != 0) {
            hold(portals.get(dominant));
        }
    }

    public void drop(int portalKey) {
        portals.remove(portalKey);
        if (dominant == portalKey) {
            restore();
            dominant = 0;
        }
    }

    public void clear() {
        restore();
        dominant = 0;
        portals.clear();
    }

    public void discard() {
        saved = null;
        dominant = 0;
        portals.clear();
    }

    public void dominanceBlocks(double value) {
        dominanceBlocks = value;
    }

    public int dominant() {
        return dominant;
    }

    public long weatherParticles() {
        return weatherParticles;
    }

    public boolean holds(int portalKey) {
        return portals.containsKey(portalKey);
    }

    private void hold(Received received) {
        if (received == null) {
            return;
        }
        ClientViewMessage.Atmosphere atmosphere = received.atmosphere;
        if ((atmosphere.flags() & ClientViewMessage.Atmosphere.FLAG_WEATHER) != 0) {
            world.weather(atmosphere.rain(), atmosphere.thunder());
            saved.heldRain = world.rain();
            saved.heldThunder = world.thunder();
            saved.weatherHeld = true;
        }
        if ((atmosphere.flags() & ClientViewMessage.Atmosphere.FLAG_TIME) != 0 && world.hasClock()) {
            world.clock(atmosphere.dayTime() + Math.max(0L, world.gameTime() - received.gameTime));
            saved.clockHeld = true;
        }
    }

    private void observeLocalWeather() {
        Saved current = saved;
        if (current == null || !current.weatherHeld) {
            return;
        }
        float rain = world.rain();
        float thunder = world.thunder();
        if (Float.compare(rain, current.heldRain) != 0 || Float.compare(thunder, current.heldThunder) != 0) {
            current.rain = rain;
            current.thunder = thunder;
        }
    }

    private void restore() {
        observeLocalWeather();
        Saved previous = saved;
        saved = null;
        if (previous == null) {
            return;
        }
        if (previous.weatherHeld) {
            world.weather(previous.rain, previous.thunder);
        }
        if (previous.clockHeld && world.hasClock()) {
            world.clock(world.gameTime() + previous.clockOffset);
        }
    }

    private void weather(ClientPortal portal, ClientViewMessage.Atmosphere atmosphere) {
        if ((atmosphere.flags() & ClientViewMessage.Atmosphere.FLAG_WEATHER) == 0 || atmosphere.rain() < RAIN_THRESHOLD
            || clientTick % WEATHER_BURST_TICKS != 0) {
            return;
        }
        ClientViewSweep sweep = portal.sweep();
        ClientPortalContent content = portal.content();
        PlateBox bounds = sweep.bounds();
        if (bounds.cells() == 0L || sweep.appliedCount() == 0) {
            return;
        }
        int particles = atmosphere.thunder() > RAIN_THRESHOLD ? STORM_PARTICLES : RAIN_PARTICLES;
        for (int attempt = 0; attempt < particles * SAMPLE_ATTEMPTS && particles > 0; attempt++) {
            int x = bounds.minX() + random.nextInt(Math.max(1, bounds.sizeX()));
            int y = bounds.minY() + random.nextInt(Math.max(1, bounds.sizeY()));
            int z = bounds.minZ() + random.nextInt(Math.max(1, bounds.sizeZ()));
            if (!sweep.applied(x, y, z) || content.paletteIdAt(x, y, z) != ClientViewProtocol.PALETTE_AIR) {
                continue;
            }
            world.particle(RAIN_PARTICLE, x + 0.5D, y + 0.5D, z + 0.5D, 0.4D, 0.0D, 1);
            weatherParticles++;
            particles--;
        }
    }

    private static boolean inside(ClientPortal portal, double eyeX, double eyeY, double eyeZ) {
        AxisAlignedBB area = portal.geometry().apertureArea();
        Direction facing = portal.geometry().facingDirection();
        return (facing.x() != 0 || within(eyeX, area.getXa(), area.getXb()))
            && (facing.y() != 0 || within(eyeY, area.getYa(), area.getYb()))
            && (facing.z() != 0 || within(eyeZ, area.getZa(), area.getZb()));
    }

    private static boolean within(double value, double a, double b) {
        return value >= Math.min(a, b) - LATERAL_MARGIN && value <= Math.max(a, b) + LATERAL_MARGIN;
    }

    private record Received(ClientViewMessage.Atmosphere atmosphere, long gameTime) {
    }

    private static final class Saved {
        private final long clockOffset;
        private float rain;
        private float thunder;
        private float heldRain;
        private float heldThunder;
        private boolean weatherHeld;
        private boolean clockHeld;

        private Saved(float rain, float thunder, long clockOffset) {
            this.rain = rain;
            this.thunder = thunder;
            this.clockOffset = clockOffset;
        }
    }
}
