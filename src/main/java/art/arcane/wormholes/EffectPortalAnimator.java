package art.arcane.wormholes;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.effects.PortalAnimation;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.function.BooleanSupplier;

final class EffectPortalAnimator {
    private final EffectDisplayRegistry displays;

    EffectPortalAnimator(EffectDisplayRegistry displays) {
        this.displays = displays;
    }

    void playClose(World world, Location corner, double sx, double sy, double sz, BooleanSupplier active, BooleanSupplier audible) {
        start(world, corner.clone().add(sx / 2, sy / 2, sz / 2), new PortalAnimation.Options(PortalAnimation.Mode.CLOSE,
            new GeometryVector(corner.getX() + sx / 2, corner.getY() + sy / 2, corner.getZ() + sz / 2), new GeometryVector(sx, sy, sz),
            Settings.VISUAL_QUALITY_PROFILE, Settings.ENABLE_PARTICLES, Settings.PORTAL_SOUND_VOLUME_MULTIPLIER,
            () -> !displays.isClosing() && active.getAsBoolean(), audible, List.of()));
    }

    void playOpenPrelude(World world, Location center, double sx, double sy, double sz, BooleanSupplier active, BooleanSupplier audible) {
        start(world, center, options(PortalAnimation.Mode.PRELUDE, center, new GeometryVector(sx, sy, sz), active, audible));
    }

    void playKawoosh(World world, Location center, double sx, double sy, double sz, BooleanSupplier active, BooleanSupplier audible) {
        start(world, center, options(PortalAnimation.Mode.IMPACT, center, new GeometryVector(sx, sy, sz), active, audible));
    }

    void playKawooshSounds(World world, Location center, BooleanSupplier active, BooleanSupplier audible) {
        start(world, center, options(PortalAnimation.Mode.SOUNDS, center, new GeometryVector(1, 1, 1), active, audible));
    }

    private PortalAnimation.Options options(PortalAnimation.Mode mode, Location center, GeometryVector size, BooleanSupplier active, BooleanSupplier audible) {
        return new PortalAnimation.Options(mode, new GeometryVector(center.getX(), center.getY(), center.getZ()), size,
            Settings.VISUAL_QUALITY_PROFILE, Settings.ENABLE_PARTICLES, Settings.PORTAL_SOUND_VOLUME_MULTIPLIER,
            () -> !displays.isClosing() && active.getAsBoolean(), audible, List.of());
    }

    private void start(World world, Location center, PortalAnimation.Options options) {
        if (world == null || center == null || !options.active().getAsBoolean()) {
            return;
        }
        AnimationHost host = new AnimationHost(world, center);
        host.animation = new PortalAnimation<>(options, host);
        host.run();
    }

    private final class AnimationHost implements PortalAnimation.Host<BlockDisplay>, Runnable {
        private final World world;
        private final Location center;
        private PortalAnimation<BlockDisplay> animation;

        private AnimationHost(World world, Location center) {
            this.world = world;
            this.center = center;
        }

        @Override
        public void run() {
            if (animation.tick() && !FoliaScheduler.runRegion(Wormholes.instance, center, this, 1L)) {
                animation.schedulingRejected();
            }
        }

        @Override
        public void particle(PortalAnimation.ParticleEmission emission) {
            Particle particle = switch (emission.type()) {
                case PORTAL -> Particle.PORTAL;
                case REVERSE_PORTAL -> Particle.REVERSE_PORTAL;
                case STREAM_DUST, ARM_DUST -> Particle.DUST_COLOR_TRANSITION;
                case END_ROD -> Particle.END_ROD;
                case ENCHANT -> Particle.ENCHANT;
                case GLASS_SHARD -> Particle.BLOCK;
                case SCULK_SOUL -> Particle.SCULK_SOUL;
                case PALE_FLASH, PURPLE_FLASH -> Particle.FLASH;
                case CRACK_DUST, BRANCHLET_DUST -> Particle.DUST;
            };
            Object data = switch (emission.type()) {
                case STREAM_DUST, ARM_DUST -> new Particle.DustTransition(Color.fromRGB(185, 105, 255), Color.fromRGB(20, 5, 35), emission.type() == PortalAnimation.Particle.STREAM_DUST ? .8f : .9f);
                case GLASS_SHARD -> Material.GLASS.createBlockData();
                case PALE_FLASH -> Color.fromRGB(220, 235, 255);
                case PURPLE_FLASH -> Color.fromRGB(190, 130, 255);
                case BRANCHLET_DUST -> new Particle.DustOptions(Color.fromRGB(210, 230, 255), .55f);
                case CRACK_DUST -> new Particle.DustOptions(Color.fromRGB(235, 245, 255), .75f);
                default -> null;
            };
            world.spawnParticle(particle, emission.position().x(), emission.position().y(), emission.position().z(), emission.count(),
                emission.spread().x(), emission.spread().y(), emission.spread().z(), emission.speed(), data);
        }

        @Override
        public void sound(PortalAnimation.SoundEmission sound) {
            world.playSound(new Location(world, sound.position().x(), sound.position().y(), sound.position().z()), sound.id(), SoundCategory.BLOCKS, sound.volume(), sound.pitch());
        }

        @Override
        public BlockDisplay spawn(PortalAnimation.DisplaySpec specification) {
            BlockDisplay display = world.spawn(center, BlockDisplay.class, entity -> {
                entity.setBlock(Bukkit.createBlockData(specification.blockState()));
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setPersistent(false);
                entity.setViewRange(2.5f);
                transform(entity, specification);
            });
            displays.track(display);
            return display;
        }

        @Override
        public void transform(BlockDisplay display, PortalAnimation.DisplaySpec specification) {
            Vector3f scale = new Vector3f((float) specification.scale().x(), (float) specification.scale().y(), (float) specification.scale().z());
            Quaternionf rotation = new Quaternionf().rotationAxis(specification.rotation(), specification.normal() == 0 ? 1 : 0, specification.normal() == 1 ? 1 : 0, specification.normal() == 2 ? 1 : 0);
            Vector3f half = new Vector3f(scale).mul(.5f).rotate(rotation);
            Vector3f translation = new Vector3f((float) (specification.center().x() - center.getX()), (float) (specification.center().y() - center.getY()), (float) (specification.center().z() - center.getZ())).sub(half);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(2);
            display.setTransformation(new Transformation(translation, rotation, scale, new Quaternionf()));
        }

        @Override
        public void remove(BlockDisplay display) {
            displays.remove(display);
        }
    }
}
