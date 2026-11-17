package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.PortalFeatureRenderer;
import art.arcane.wormholes.modded.client.render.PortalScene;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.longs.LongIterable;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockAndTintGetter;

import java.util.Objects;

/**
 * Turns the session's streamed mesh sections into {@link PortalScene}s for {@link ClientPortalRenderer}, one per portal
 * whose source is the level the player is in. 1.20.1 has no render-state extraction, so each scene draws its block
 * entities and entities directly when the renderer asks for its features.
 */
public final class ClientMeshViews {
    private final Int2ObjectOpenHashMap<Scene> scenes = new Int2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet changedNeighbors = new LongLinkedOpenHashSet();

    public void update(ClientViewSession session, ClientLevel level) {
        String levelWorld = level.dimension().location().toString();
        session.bindSourceWorld(levelWorld);
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
            String boundSource = session.rootSourceWorld(portal.portalKey());
            String sourceWorld = session.has(ClientViewExtensions.ROOT_SOURCE) || boundSource != null ? boundSource
                : scene == null ? levelWorld : scene.sourceWorld;
            if (sourceWorld == null || !sourceWorld.equals(levelWorld) || scene != null && !scene.sourceWorld.equals(sourceWorld)) {
                continue;
            }
            EnvironmentState environment = session.environment(portal.portalKey());
            OpticTransform transform = environment == null ? null : environment.transform();
            EnvironmentState.Dimension dimension = environment == null ? null : environment.dimension();
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
                scene = new Scene(portal.portalKey(), portal.geometry(), view, level, sourceWorld, features, session, transform, dimension,
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
                            long neighbor = SectionPos.asLong(SectionPos.x(section) + x, SectionPos.y(section) + y, SectionPos.z(section) + z);
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
        if (scene == null || scene.level != entity.level() || scene.sourceExpired() || scene.session.meshes().view(portalKey) != scene.view) {
            return false;
        }
        EnvironmentState environment = scene.environment();
        if (environment == null) {
            return false;
        }
        scene.features.tickEntity(entity, environment.transform());
        return true;
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

    private record Scene(int portalKey, ApertureDescriptor surfaceGeometry, ClientMeshSections.View view, ClientLevel level,
                         String sourceWorld, ClientMeshEntities features, ClientViewSession session, OpticTransform transform,
                         EnvironmentState.Dimension dimension, ClientMeshSections.Identity identity, int blendRadius,
                         BlockBox bounds, PortalScene.MeshIdentity meshContext) implements PortalScene {
        @Override
        public ClientLevel sourceLevel() {
            return level;
        }

        @Override
        public boolean sourceExpired() {
            return session.portal(portalKey) == null || session.meshes().view(portalKey) != view
                || session.has(ClientViewExtensions.ROOT_SOURCE) && !sourceWorld.equals(session.rootSourceWorld(portalKey));
        }

        @Override
        public ApertureDescriptor geometry() {
            return session.portal(portalKey).geometry();
        }

        @Override
        public ViewStreamMessage.PortalAffine affine() {
            return session.affine(portalKey);
        }

        @Override
        public EnvironmentState environment() {
            return session.environment(portalKey);
        }

        @Override
        public void renderFeatures(PortalFeatureRenderer renderer) {
            EnvironmentState environment = environment();
            if (environment != null) {
                features.render(portalKey, renderer, environment.transform());
            }
        }

        @Override
        public BlockAndTintGetter world(long sectionKey) {
            return new ClientMeshWorld(new ClientMeshWorld.Snapshot(view, sectionKey, level.registryAccess(), environment(), blendRadius));
        }

        @Override
        public PortalScene.MeshIdentity meshContext() {
            if (meshContext == null) {
                return null;
            }
            EnvironmentState current = environment();
            return current != null && identity.equals(view.identity()) && bounds.equals(view.bounds())
                && dimension.equals(current.dimension()) && identity.matchesEnvironment(current) ? meshContext : null;
        }

        @Override
        public PortalScene.MeshIdentity meshIdentity(long sectionKey) {
            return ClientMeshWorld.meshIdentity(new ClientMeshWorld.Snapshot(view, sectionKey, level.registryAccess(), environment(), blendRadius));
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
