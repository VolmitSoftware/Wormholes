package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.PortalScene;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.longs.LongIterable;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.SectionPos;

public final class ClientMeshViews {
    private final Int2ObjectOpenHashMap<Scene> scenes = new Int2ObjectOpenHashMap<>();

    public void update(ClientViewSession session, ClientLevel level) {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        for (IntIterator iterator = scenes.keySet().iterator(); iterator.hasNext();) {
            int key = iterator.nextInt();
            if (!session.active() || session.portal(key) == null || session.meshes().view(key) == null) {
                renderer.remove(key);
                iterator.remove();
            }
        }
        if (!session.active()) {
            return;
        }
        for (ClientPortal portal : session.portals().values()) {
            ClientMeshSections.View view = session.meshes().view(portal.portalKey());
            if (view == null) {
                continue;
            }
            Scene scene = scenes.get(portal.portalKey());
            ClientViewEnvironment environment = session.environment(portal.portalKey());
            ClientViewEnvironment.Transform transform = environment == null ? null : environment.transform();
            if (scene == null || scene.view != view || scene.level != level || !scene.surfaceGeometry.sameSurface(portal.geometry()) || !Objects.equals(scene.transform, transform)) {
                scene = new Scene(portal.portalKey(), portal.geometry(), view, level, new ClientMeshEntities(view, level), session, transform);
                scenes.put(portal.portalKey(), scene);
                renderer.replaceScene(portal.portalKey(), scene);
            }
            for (LongIterator iterator = view.changed().iterator(); iterator.hasNext();) {
                long section = iterator.nextLong();
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        for (int x = -1; x <= 1; x++) {
                            renderer.invalidate(portal.portalKey(), SectionPos.asLong(SectionPos.x(section) + x,
                                SectionPos.y(section) + y, SectionPos.z(section) + z));
                        }
                    }
                }
            }
            view.changed().clear();
        }
    }

    public void extract(Camera camera, float partialTick) {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        for (Scene scene : scenes.values()) {
            if (scene.session.meshes().view(scene.portalKey) != scene.view || !renderer.available(scene.portalKey)) {
                continue;
            }
            try {
                ClientViewEnvironment environment = scene.environment();
                if (environment != null) {
                    scene.features.extract(scene.portalKey, camera, partialTick, environment.transform());
                    renderer.featuresReady(scene.portalKey);
                }
            } catch (RuntimeException failure) {
                renderer.featureFailed(scene.portalKey, failure);
            }
        }
    }

    public void clear() {
        scenes.clear();
        ClientPortalRenderer.instance().clear();
    }

    private record Scene(int portalKey, ClientPortalGeometry surfaceGeometry, ClientMeshSections.View view, ClientLevel level,
                         ClientMeshEntities features, ClientViewSession session, ClientViewEnvironment.Transform transform) implements PortalScene {
        @Override
        public ClientPortalGeometry geometry() {
            return session.portal(portalKey).geometry();
        }

        @Override
        public ClientViewEnvironment environment() {
            return session.environment(portalKey);
        }

        @Override
        public List<BlockEntityRenderState> blockEntities() {
            return features.blockEntities();
        }

        @Override
        public List<EntityRenderState> entities() {
            return features.entities();
        }

        @Override
        public BlockAndTintGetter world(long sectionKey) {
            return new ClientMeshWorld(new ClientMeshWorld.Snapshot(view, sectionKey, level.registryAccess().lookupOrThrow(Registries.BIOME),
                environment(), Minecraft.getInstance().options.biomeBlendRadius().get()));
        }

        @Override
        public LongIterable sectionKeys() {
            return view.sectionKeys();
        }

        @Override
        public long revision(long sectionKey) {
            ClientMeshSections.Section section = view.section(sectionKey);
            return section == null ? -1 : section.revision();
        }
    }
}
