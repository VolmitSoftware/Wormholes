package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.effects.PortalAnimation;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.Blocks;

public final class MinecraftAnimationParticles {
    private static final ParticleOptions STREAM_DUST = new DustColorTransitionOptions(0xb969ff, 0x140523, .8f);
    private static final ParticleOptions ARM_DUST = new DustColorTransitionOptions(0xb969ff, 0x140523, .9f);
    private static final ParticleOptions GLASS_SHARD = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.GLASS.defaultBlockState());
    private static final ParticleOptions PALE_FLASH = ColorParticleOption.create(ParticleTypes.FLASH, 0xffdcebff);
    private static final ParticleOptions PURPLE_FLASH = ColorParticleOption.create(ParticleTypes.FLASH, 0xffbe82ff);
    private static final ParticleOptions WHITE_FLASH = ColorParticleOption.create(ParticleTypes.FLASH, 0xffffffff);
    private static final ParticleOptions BRANCHLET_DUST = new DustParticleOptions(0xd2e6ff, .55f);
    private static final ParticleOptions CRACK_DUST = new DustParticleOptions(0xebf5ff, .75f);

    private MinecraftAnimationParticles() {
    }

    public static ParticleOptions options(PortalAnimation.Particle particle) {
        return switch (particle) {
            case PORTAL -> ParticleTypes.PORTAL;
            case REVERSE_PORTAL -> ParticleTypes.REVERSE_PORTAL;
            case STREAM_DUST -> STREAM_DUST;
            case ARM_DUST -> ARM_DUST;
            case END_ROD -> ParticleTypes.END_ROD;
            case ENCHANT -> ParticleTypes.ENCHANT;
            case GLASS_SHARD -> GLASS_SHARD;
            case SCULK_SOUL -> ParticleTypes.SCULK_SOUL;
            case PALE_FLASH -> PALE_FLASH;
            case PURPLE_FLASH -> PURPLE_FLASH;
            case BRANCHLET_DUST -> BRANCHLET_DUST;
            case CRACK_DUST -> CRACK_DUST;
            case WHITE_FLASH -> WHITE_FLASH;
            case ELECTRIC_SPARK -> ParticleTypes.ELECTRIC_SPARK;
        };
    }
}
