package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.mixin.client.IrisPortalPipelineAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderingAccess;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class IrisPipelines {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> FAILED = new HashSet<>();

    private IrisPipelines() {
    }

    public static boolean loadingStep() {
        List<String> pending = pending();
        if (!pending.isEmpty()) {
            create(pending.getFirst());
            return false;
        }
        if (Iris.getCurrentPack().isPresent()) {
            IrisMeshFrame.warm();
        }
        return true;
    }

    public static void createAll() {
        FAILED.clear();
        for (String dimension : pending()) {
            create(dimension);
        }
        if (Iris.getCurrentPack().isPresent()) {
            IrisMeshFrame.warm();
        }
    }

    private static List<String> pending() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        Optional<ShaderPack> pack = Iris.getCurrentPack();
        if (connection == null || minecraft.level == null || pack.isEmpty()) {
            return List.of();
        }
        Registry<DimensionType> types = connection.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
        List<String> dimensions = new ArrayList<>(connection.levels().size());
        for (ResourceKey<Level> level : connection.levels()) {
            dimensions.add(text(resolve(level.identifier(), types, pack.get())));
        }
        Map<NamespacedId, WorldRenderingPipeline> existing = ((IrisPortalPipelineAccess) Iris.getPipelineManager()).wormholes$pipelines();
        Set<String> created = new HashSet<>(existing.size());
        for (NamespacedId dimension : existing.keySet()) {
            created.add(text(dimension));
        }
        List<String> order = IrisPipelineSchedule.order(text(Iris.getCurrentDimension()), dimensions);
        return IrisPipelineSchedule.pending(order, created, FAILED);
    }

    private static void create(String dimension) {
        PipelineManager manager = Iris.getPipelineManager();
        NamespacedId target = new NamespacedId(dimension);
        boolean current = target.equals(Iris.getCurrentDimension());
        WorldRenderingPipeline selected = manager.getPipelineNullable();
        long started = System.nanoTime();
        try {
            WorldRenderingPipeline pipeline = manager.preparePipeline(target);
            if (!current && pipeline instanceof IrisRenderingPipeline) {
                ((IrisPortalRenderingAccess) pipeline).wormholes$initializedBlockIds(true);
            }
            LOGGER.info("Iris pipeline {} ready in {} ms", dimension, (System.nanoTime() - started) / 1_000_000L);
        } catch (RuntimeException failure) {
            FAILED.add(dimension);
            LOGGER.error("Unable to create the Iris pipeline for {}; portal views into it compile it on first use", dimension, failure);
        } finally {
            if (!current) {
                ((IrisPortalPipelineAccess) manager).wormholes$pipeline(selected);
            }
        }
    }

    private static NamespacedId resolve(Identifier level, Registry<DimensionType> types, ShaderPack pack) {
        NamespacedId dimension = new NamespacedId(level.getNamespace(), level.getPath());
        if (pack.getDimensionMap().containsKey(dimension)) {
            return dimension;
        }
        DimensionType.Skybox skybox = types.get(ResourceKey.create(Registries.DIMENSION_TYPE, level))
            .map(Holder::value).map(DimensionType::skybox).orElse(DimensionType.Skybox.OVERWORLD);
        return switch (skybox) {
            case END -> DimensionId.END;
            case OVERWORLD -> DimensionId.OVERWORLD;
            case NONE -> dimension;
        };
    }

    private static String text(NamespacedId dimension) {
        return dimension.getNamespace() + ":" + dimension.getName();
    }
}
