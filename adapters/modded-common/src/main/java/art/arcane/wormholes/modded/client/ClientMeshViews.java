package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.PortalScene;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.plate.PlateBox;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.longs.LongIterable;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.SectionPos;

public final class ClientMeshViews {
    private final Int2ObjectOpenHashMap<Scene> scenes = new Int2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet changedNeighbors = new LongLinkedOpenHashSet();

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
            ClientViewEnvironment.Dimension dimension = environment == null ? null : environment.dimension();
            ClientMeshSections.Identity identity = view.identity();
            int blendRadius = Minecraft.getInstance().options.biomeBlendRadius().get();
            boolean terrainUnchanged = scene != null && scene.view == view && Objects.equals(scene.transform, transform)
                && Objects.equals(scene.identity, identity) && Objects.equals(scene.dimension, dimension)
                && Objects.equals(scene.bounds, view.bounds()) && scene.blendRadius == blendRadius;
            if (!terrainUnchanged || scene.level != level || !scene.surfaceGeometry.sameSurface(portal.geometry())) {
                boolean levelChanged = scene != null && scene.level != level;
                boolean retain = terrainUnchanged && scene.surfaceGeometry.sameContentSurface(portal.geometry())
                    && (!levelChanged || identity != null && scene.level.registryAccess() == level.registryAccess());
                ClientMeshEntities features = retain && !levelChanged ? scene.features : new ClientMeshEntities(view, level);
                PortalScene.MeshIdentity context = ClientMeshWorld.meshContext(new ClientMeshWorld.Snapshot(view, 0L,
                    level.registryAccess(), environment, blendRadius));
                scene = new Scene(portal.portalKey(), portal.geometry(), view, level, features, session, transform, dimension,
                    identity, blendRadius, view.bounds(), context);
                scenes.put(portal.portalKey(), scene);
                if (retain) {
                    renderer.refreshScene(portal.portalKey(), scene, levelChanged);
                } else {
                    renderer.replaceScene(portal.portalKey(), scene);
                    view.changed().clear();
                    continue;
                }
            }
            changedNeighbors.clear();
            boolean singleChange = view.changed().size() <= 1;
            for (LongIterator iterator = view.changed().iterator(); iterator.hasNext();) {
                long section = iterator.nextLong();
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        for (int x = -1; x <= 1; x++) {
                            if (x == 0 && y == 0 && z == 0) {
                                continue;
                            }
                            long neighbor = SectionPos.asLong(SectionPos.x(section) + x,
                                SectionPos.y(section) + y, SectionPos.z(section) + z);
                            if (singleChange || changedNeighbors.add(neighbor)) {
                                renderer.invalidate(portal.portalKey(), neighbor, false);
                            }
                        }
                    }
                }
            }
            for (LongIterator iterator = view.changed().iterator(); iterator.hasNext();) {
                renderer.invalidate(portal.portalKey(), iterator.nextLong(), true);
            }
            view.changed().clear();
        }
    }

    public boolean tickEntity(int portalKey, Entity entity) {
        Scene scene = scenes.get(portalKey);
        if (scene == null || scene.level != entity.level() || scene.session.meshes().view(portalKey) != scene.view) {
            return false;
        }
        ClientViewEnvironment environment = scene.environment();
        if (environment == null) {
            return false;
        }
        scene.features.tickEntity(entity, environment.transform());
        return true;
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

    public void detach() {
        ClientPortalRenderer renderer = ClientPortalRenderer.instance();
        for (int key : scenes.keySet()) {
            renderer.remove(key);
        }
        scenes.clear();
    }

    public void clear() {
        scenes.clear();
        ClientPortalRenderer.instance().clear();
    }

    private record Scene(int portalKey, ClientPortalGeometry surfaceGeometry, ClientMeshSections.View view, ClientLevel level,
                         ClientMeshEntities features, ClientViewSession session, ClientViewEnvironment.Transform transform,
                         ClientViewEnvironment.Dimension dimension, ClientMeshSections.Identity identity, int blendRadius,
                         PlateBox bounds, PortalScene.MeshIdentity meshContext) implements PortalScene {
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
        public Predicate<EntityRenderState> entityVisibility(CameraRenderState camera, Frustum frustum) {
            return features.entityVisibility(camera, frustum, transform);
        }

        @Override
        public BlockAndTintGetter world(long sectionKey) {
            return new ClientMeshWorld(new ClientMeshWorld.Snapshot(view, sectionKey, level.registryAccess(),
                environment(), blendRadius));
        }

        @Override
        public PortalScene.MeshIdentity meshContext() {
            if (meshContext == null) {
                return null;
            }
            ClientViewEnvironment current = environment();
            return current != null && identity.equals(view.identity()) && bounds.equals(view.bounds())
                && dimension.equals(current.dimension()) && identity.matchesEnvironment(current) ? meshContext : null;
        }

        @Override
        public PortalScene.MeshIdentity meshIdentity(long sectionKey) {
            return ClientMeshWorld.meshIdentity(new ClientMeshWorld.Snapshot(view, sectionKey, level.registryAccess(),
                environment(), blendRadius));
        }

        @Override
        public boolean matchesMeshIdentity(long sectionKey, PortalScene.MeshIdentity retained) {
            return ClientMeshWorld.matchesMeshIdentity(view, sectionKey, level.registryAccess(), meshContext(), retained);
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
