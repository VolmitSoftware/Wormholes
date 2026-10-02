package art.arcane.wormholes.modded.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import java.util.Objects;

public final class PortalIrisBiomes {
    private PortalIrisBiomes() {
    }

    public static Holder<Biome> current() {
        PortalShaderContext.View view = Objects.requireNonNull(PortalShaderContext.current());
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, Identifier.parse(view.environment().world().biomeKey()));
        return Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(key);
    }

    public static int precipitation() {
        PortalShaderContext.View view = Objects.requireNonNull(PortalShaderContext.current());
        return switch (current().value().getPrecipitationAt(BlockPos.containing(view.camera().position()), view.environment().world().seaLevel())) {
            case NONE -> 0;
            case RAIN -> 1;
            case SNOW -> 2;
        };
    }
}
