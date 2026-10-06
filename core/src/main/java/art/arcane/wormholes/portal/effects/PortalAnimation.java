package art.arcane.wormholes.portal.effects;

import art.arcane.wormholes.config.VisualQualityProfile;
import art.arcane.optics.math.Vec3;
import art.arcane.wormholes.portal.PortalAnimationPlan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.random.RandomGenerator;

public final class PortalAnimation<D> {
    private static final Vec3 ZERO = new Vec3(0, 0, 0);
    private static final double MIN_PLANE_EXTENT = 1.0E-3;
    private final Options options;
    private final Host<D> host;
    private final RandomGenerator random;
    private final int normal;
    private final int planeA;
    private final int planeB;
    private final double halfA;
    private final double halfB;
    private final double[] extent;
    private final double[] angle;
    private final double[] reach;
    private final double[] bend;
    private final List<MovingDisplay<D>> displays = new ArrayList<>();
    private final double[] branchletAngle;
    private final double[] branchletStart;
    private final double[] branchletReach;
    private final int[] branchletTick;
    private boolean boomPlayed;
    private int age;
    private boolean closed;

    public PortalAnimation(Options options, Host<D> host) {
        this.options = options;
        this.host = host;
        random = RandomGenerator.getDefault();
        extent = new double[] { options.size().x(), options.size().y(), options.size().z() };
        normal = normalAxis(options.size());
        planeA = planeA(normal);
        planeB = planeB(normal);
        halfA = Math.max(0.6, extent[planeA] / 2 + 0.35);
        halfB = Math.max(0.6, extent[planeB] / 2 + 0.35);
        int count = options.mode() == Mode.CLOSE ? PortalAnimationPlan.closeEffectPlan(options.quality()).branches()
            : PortalAnimationPlan.openingRingPoints(options.quality());
        angle = new double[count];
        reach = new double[count];
        bend = new double[count];
        branchletAngle = new double[count];
        branchletStart = new double[count];
        branchletReach = new double[count];
        branchletTick = new int[count];
        for (int index = 0; index < count; index++) {
            if (options.mode() == Mode.CLOSE) {
                angle[index] = Math.PI * 2 * index / count + (random.nextDouble() - .5) * .45;
                reach[index] = .6 + random.nextDouble() * .4;
                bend[index] = (random.nextDouble() - .5) * .7;
                double side = random.nextDouble() < .5 ? -1 : 1;
                branchletAngle[index] = angle[index] + side * (.55 + random.nextDouble() * .5);
                branchletStart[index] = .35 + random.nextDouble() * .3;
                branchletReach[index] = .18 + random.nextDouble() * .22;
                branchletTick[index] = 3 + (int) (random.nextDouble() * 4);
            } else {
                angle[index] = Math.PI * 2 * index / count + (random.nextDouble() - .5) * .8;
                reach[index] = 1.2 + random.nextDouble();
                bend[index] = 1.2 + random.nextDouble() * .5;
            }
        }
    }

    public boolean tick() {
        if (closed || !options.active().getAsBoolean()) {
            close();
            return false;
        }
        int tick = age++;
        boolean running = switch (options.mode()) {
            case OPEN -> opening(tick, true);
            case PRELUDE -> opening(tick, false);
            case IMPACT -> impact(tick);
            case SOUNDS -> impactSounds(tick);
            case CLOSE -> closing(tick);
            case FORMATION -> formation(tick);
            case GLITCH -> glitch();
        };
        if (!running) {
            close();
        }
        return running;
    }

    public void schedulingRejected() {
        if (!closed && options.active().getAsBoolean()) {
            if (options.particles() && (options.mode() == Mode.OPEN || options.mode() == Mode.PRELUDE) && age <= 18) {
                impact(0);
            }
            if (options.mode() != Mode.CLOSE && options.mode() != Mode.FORMATION && options.mode() != Mode.GLITCH && !boomPlayed) {
                impactSounds(5);
            }
        }
        close();
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (MovingDisplay<D> display : displays) {
            host.remove(display.id());
        }
        displays.clear();
    }

    private boolean opening(int tick, boolean frameSound) {
        if (tick == 0 && frameSound) {
            sound("block.end_portal.spawn", 0.2f, 0.35f);
        }
        if (!options.particles()) {
            return impactSounds(tick);
        }
        if (tick >= 18) {
            return impact(tick - 18);
        }
        double fraction = (tick + 1) / 18.0;
        int count = PortalAnimationPlan.openingRingPoints(options.quality());
        for (int index = 0; index < count; index++) {
            double theta = angle[index] + fraction * reach[index] * Math.PI * 2;
            double radius = PortalAnimationPlan.ellipseRadius(halfA, halfB, theta) * Math.pow(1 - fraction, bend[index]);
            Vec3 point = point(radius * Math.cos(theta), radius * Math.sin(theta));
            particle(index % 2 == 0 ? Particle.PORTAL : Particle.REVERSE_PORTAL, point, 1, new Vec3(.03, .03, .03), .02);
            if (tick % 2 == 0) {
                particle(Particle.STREAM_DUST, point, 1, ZERO, 0);
            }
        }
        return true;
    }

    private boolean impact(int tick) {
        impactSounds(tick);
        if (!options.particles() || tick >= 13) {
            return tick < 5;
        }
        PortalAnimationPlan.KawooshPlan plan = PortalAnimationPlan.kawooshPlan(options.quality());
        if (tick == 0) {
            particle(Particle.PURPLE_FLASH, options.center(), 1, ZERO, 0);
            particle(Particle.REVERSE_PORTAL, options.center(), plan.impactReverse(), new Vec3(.25, .45, .25), .75);
            particle(Particle.END_ROD, options.center(), plan.impactEndRod(), new Vec3(.15, .15, .15), .3);
            return true;
        }
        int frame = tick - 1;
        double fraction = (frame + 1) / 12.0;
        int points = Math.max(3, (int) Math.round(plan.armPoints() * fraction));
        for (int arm = 0; arm < plan.arms(); arm++) {
            double offset = Math.PI * 2 * arm / plan.arms();
            for (int index = 1; index <= points; index++) {
                double along = fraction * index / points;
                double theta = offset + along * Math.PI * 1.25;
                double radius = PortalAnimationPlan.ellipseRadius(halfA, halfB, theta) * along;
                particle(index == points ? Particle.END_ROD : Particle.ARM_DUST,
                    point(radius * Math.cos(theta), radius * Math.sin(theta)), 1, ZERO, index == points ? .02 : 0);
            }
        }
        if (frame < 5) {
            for (int index = 0; index < plan.surgeCount(); index++) {
                double[] direction = new double[3];
                direction[normal] = index % 2 == 0 ? 1 : -1;
                direction[planeA] = (random.nextDouble() - .5) * .45;
                direction[planeB] = (random.nextDouble() - .5) * .45;
                particle(Particle.END_ROD, options.center(), 0, vector(direction), .6 - frame * .07);
            }
        } else {
            double spread = Math.max(.15, .9 * (1 - fraction));
            particle(Particle.REVERSE_PORTAL, options.center(), 6, new Vec3(spread, spread, spread), .2);
            particle(Particle.ENCHANT, options.center(), 3, new Vec3(spread, spread, spread), .05);
        }
        return true;
    }

    private boolean impactSounds(int tick) {
        if (tick == 0) {
            sound("block.end_portal.spawn", .225f, .85f);
            sound("block.beacon.activate", .2f, .55f);
        }
        if (tick == 5 && !boomPlayed) {
            boomPlayed = true;
            sound("entity.warden.sonic_boom", .075f, .8f);
        }
        return tick < 5;
    }

    private boolean closing(int tick) {
        PortalAnimationPlan.CloseEffectPlan plan = PortalAnimationPlan.closeEffectPlan(options.quality());
        if (tick == 0) {
            sound("block.ender_chest.close", .36f, .55f);
            if (!options.particles()) {
                sound("block.glass.break", .3f, .9f);
                return false;
            }
            double[] scale = new double[3];
            scale[normal] = .18;
            scale[planeA] = Math.max(.25, extent[planeA]);
            scale[planeB] = Math.max(.25, extent[planeB]);
            D display = host.spawn(new DisplaySpec("minecraft:glass", options.center(), vector(scale), 0, normal));
            displays.add(new MovingDisplay<>(display, 0, 0, 0, 0, 0, 0, 0, 0));
            return true;
        }
        if (tick > 10) {
            for (MovingDisplay<D> display : displays) {
                host.remove(display.id());
            }
            displays.clear();
            for (int index = 0; index < plan.shards(); index++) {
                double theta = random.nextDouble() * Math.PI * 2;
                double radius = PortalAnimationPlan.ellipseRadius(Math.max(.3, extent[planeA] / 2), Math.max(.3, extent[planeB] / 2), theta) * (.15 + random.nextDouble() * .85);
                double[] velocity = PortalAnimationPlan.outwardShardVelocity(normal, planeA, planeB, Math.cos(theta), Math.sin(theta), index % 2 == 0 ? 1 : -1);
                particle(Particle.GLASS_SHARD, point(radius * Math.cos(theta), radius * Math.sin(theta)), 0, vector(velocity), .35);
            }
            particle(Particle.REVERSE_PORTAL, options.center(), plan.shards(), new Vec3(.2, .3, .2), .65);
            particle(Particle.SCULK_SOUL, options.center(), 6, new Vec3(.25, .35, .25), .03);
            particle(Particle.PALE_FLASH, options.center(), 1, ZERO, 0);
            sound("block.glass.break", .54f, .8f);
            sound("block.glass.break", .33f, 1.05f);
            sound("entity.item.break", .36f, .8f);
            return false;
        }
        double fraction = tick / 10.0;
        for (int branch = 0; branch < plan.branches(); branch++) {
            for (int segment = 1; segment <= plan.segments(); segment++) {
                double along = (double) segment / plan.segments();
                double theta = angle[branch] + bend[branch] * fraction * fraction * along;
                double radius = PortalAnimationPlan.ellipseRadius(Math.max(.3, extent[planeA] / 2), Math.max(.3, extent[planeB] / 2), theta) * reach[branch] * fraction * along;
                particle(Particle.CRACK_DUST, point(radius * Math.cos(theta), radius * Math.sin(theta)), 1, ZERO, 0);
            }
        }
        int frame = tick - 1;
        for (int branch = 0; branch < plan.branches(); branch++) {
            if (frame < branchletTick[branch]) {
                continue;
            }
            double baseFraction = (branchletTick[branch] + 1) / 10.0;
            double baseAngle = angle[branch] + bend[branch] * baseFraction * baseFraction * branchletStart[branch];
            double baseLength = PortalAnimationPlan.ellipseRadius(Math.max(.3, extent[planeA] / 2), Math.max(.3, extent[planeB] / 2), baseAngle) * reach[branch] * baseFraction * branchletStart[branch];
            double growth = Math.min(1, (frame - branchletTick[branch] + 1) / 4.0);
            for (int segment = 1; segment <= 2; segment++) {
                double offshoot = PortalAnimationPlan.ellipseRadius(Math.max(.3, extent[planeA] / 2), Math.max(.3, extent[planeB] / 2), branchletAngle[branch]) * branchletReach[branch] * growth * segment / 2;
                particle(Particle.BRANCHLET_DUST, point(baseLength * Math.cos(baseAngle) + offshoot * Math.cos(branchletAngle[branch]),
                    baseLength * Math.sin(baseAngle) + offshoot * Math.sin(branchletAngle[branch])), 1, ZERO, 0);
            }
        }
        if (tick == 1) {
            sound("block.glass.break", .12f, 1.7f);
        }
        if (tick % 3 == 2) {
            sound("block.amethyst_block.hit", .15f, .7f);
        }
        return true;
    }

    private boolean formation(int tick) {
        if (tick == 0) {
            sound("block.respawn_anchor.charge", .165f, .55f);
            if (!options.particles()) {
                return false;
            }
            sound("block.end_portal.spawn", .2f, .35f);
            particle(Particle.PORTAL, options.center(), 12, new Vec3(.65, .8, .65), .35);
            int cap = PortalAnimationPlan.formationDisplayCap(options.quality());
            List<Block> selected = selectedBlocks(options.blocks(), cap);
            for (Block block : selected) {
                Vec3 relative = block.center().subtract(options.center());
                double[] position = { relative.x(), relative.y(), relative.z() };
                D display = host.spawn(new DisplaySpec(block.state(), block.center(), new Vec3(1, 1, 1), 0, normal));
                displays.add(new MovingDisplay<>(display, Math.atan2(position[planeB], position[planeA]), Math.hypot(position[planeA], position[planeB]), position[normal], 1.2 + random.nextDouble(), 1.2 + random.nextDouble() * .5, .8 + random.nextDouble() * .5, random.nextDouble() * Math.PI * 2, .2 + random.nextDouble() * .25));
            }
            return true;
        }
        if (tick > 20) {
            if (tick == 21) {
                for (MovingDisplay<D> display : displays) {
                    host.remove(display.id());
                }
                displays.clear();
            }
            return impact(tick - 21);
        }
        double fraction = tick / 20.0;
        for (int index = 0; index < displays.size(); index++) {
            MovingDisplay<D> display = displays.get(index);
            double spin = fraction * display.turns() * Math.PI * 2;
            double theta = display.angle() + spin;
            double contraction = Math.pow(1 - fraction, display.radialExponent());
            double radius = display.radius() * contraction;
            Vec3 target = point(radius * Math.cos(theta), radius * Math.sin(theta));
            double[] position = { target.x(), target.y(), target.z() };
            position[normal] += display.normalOffset() * contraction;
            double scale = Math.max(.04, Math.pow(1 - fraction, display.shrinkExponent()));
            host.transform(display.id(), new DisplaySpec("", vector(position), new Vec3(scale, scale, scale), (float) (display.wobbleAmplitude() * Math.sin(spin * 2 + display.wobblePhase())), normal));
            if (tick % 2 == 1) {
                particle(index % 2 == 0 ? Particle.PORTAL : Particle.REVERSE_PORTAL, vector(position), 1, new Vec3(.04, .04, .04), .02);
            }
        }
        return true;
    }

    private boolean glitch() {
        if (options.particles()) {
            particle(Particle.WHITE_FLASH, options.center(), 1, ZERO, 0);
            particle(Particle.REVERSE_PORTAL, options.center(), 40, new Vec3(.35, .7, .35), .25);
            particle(Particle.PORTAL, options.center(), 24, new Vec3(.3, .6, .3), .5);
            particle(Particle.ELECTRIC_SPARK, options.center(), 18, new Vec3(.4, .8, .4), .15);
        }
        return false;
    }

    private List<Block> selectedBlocks(List<Block> blocks, int cap) {
        if (blocks.size() <= cap) {
            return blocks;
        }
        Block[] sectors = new Block[cap];
        double[] radii = new double[cap];
        Arrays.fill(radii, -1);
        for (Block block : blocks) {
            Vec3 offset = block.center().subtract(options.center());
            double[] coordinates = { offset.x(), offset.y(), offset.z() };
            double a = coordinates[planeA];
            double b = coordinates[planeB];
            int sector = Math.min(cap - 1, Math.max(0, (int) Math.floor((Math.atan2(b, a) + Math.PI) / (Math.PI * 2) * cap)));
            double radius = a * a + b * b;
            if (radius > radii[sector]) {
                radii[sector] = radius;
                sectors[sector] = block;
            }
        }
        List<Block> result = new ArrayList<>(cap);
        for (Block block : sectors) {
            if (block != null) {
                result.add(block);
            }
        }
        for (Block block : blocks) {
            if (result.size() >= cap) {
                break;
            }
            if (!result.contains(block)) {
                result.add(block);
            }
        }
        return result;
    }

    public static int normalAxis(Vec3 size) {
        double x = size.x();
        double y = size.y();
        double z = size.z();
        return x <= y && x <= z ? 0 : y <= z ? 1 : 2;
    }

    public static int planeA(int normal) {
        return normal == 0 ? 1 : 0;
    }

    public static int planeB(int normal) {
        return normal == 2 ? 1 : 2;
    }

    public static Vec3 planeSize(int normal, double extentA, double extentB) {
        double[] size = new double[3];
        size[planeA(normal)] = Math.max(MIN_PLANE_EXTENT, extentA);
        size[planeB(normal)] = Math.max(MIN_PLANE_EXTENT, extentB);
        return vector(size);
    }

    private Vec3 point(double a, double b) {
        double[] position = { options.center().x(), options.center().y(), options.center().z() };
        position[planeA] += a;
        position[planeB] += b;
        return vector(position);
    }

    private static Vec3 vector(double[] value) {
        return new Vec3(value[0], value[1], value[2]);
    }

    private void particle(Particle type, Vec3 point, int count, Vec3 spread, double speed) {
        host.particle(new ParticleEmission(type, point, count, spread, speed));
    }

    private void sound(String id, float volume, float pitch) {
        if (options.audible().getAsBoolean() && options.volume() > 0) {
            host.sound(new SoundEmission(id, options.center(), (float) (volume * options.volume()), pitch));
        }
    }

    public enum Mode { OPEN, PRELUDE, IMPACT, SOUNDS, CLOSE, FORMATION, GLITCH }
    public enum Particle { PORTAL, REVERSE_PORTAL, STREAM_DUST, ARM_DUST, END_ROD, ENCHANT, GLASS_SHARD, SCULK_SOUL, PALE_FLASH, PURPLE_FLASH, CRACK_DUST, BRANCHLET_DUST, WHITE_FLASH, ELECTRIC_SPARK }
    public record Block(Vec3 center, String state) { }
    public record Options(Mode mode, Vec3 center, Vec3 size, VisualQualityProfile quality,
                          boolean particles, double volume, BooleanSupplier active, BooleanSupplier audible, List<Block> blocks) { }
    public record ParticleEmission(Particle type, Vec3 position, int count, Vec3 spread, double speed) { }
    public record SoundEmission(String id, Vec3 position, float volume, float pitch) { }
    public record DisplaySpec(String blockState, Vec3 center, Vec3 scale, float rotation, int normal) { }
    private record MovingDisplay<D>(D id, double angle, double radius, double normalOffset, double turns, double radialExponent, double shrinkExponent, double wobblePhase, double wobbleAmplitude) { }
    public interface Host<D> {
        void particle(ParticleEmission particle);
        void sound(SoundEmission sound);
        D spawn(DisplaySpec display);
        void transform(D display, DisplaySpec transform);
        void remove(D display);
    }
}
