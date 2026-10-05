package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.ClientMeshSections;
import art.arcane.wormholes.fabric.WormholesFabric;
import art.arcane.wormholes.modded.WormholesModRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.render.client.ClientViewBlockTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

final class NativeClientViewAssertions {
    private NativeClientViewAssertions() {
    }

    static int portalKey(BlockPos origin) {
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            return 0;
        }
        for (ClientPortal portal : client.session().portals().values()) {
            if (!portal.nested() && portal.geometry().originX() == origin.getX()
                && portal.geometry().originY() == origin.getY() && portal.geometry().originZ() == origin.getZ()) {
                return portal.portalKey();
            }
        }
        return 0;
    }

    static int sections(int key) {
        ClientMeshSections.View view = WormholesClient.instance().session().meshes().view(key);
        return view == null ? 0 : view.sectionKeys().size();
    }

    static boolean ready(int key) {
        return key != 0 && sections(key) > 0 && ClientPortalRenderer.instance().available(key);
    }

    static BlockPos display(int key, BlockPos destination) {
        ClientViewBlockTransform transform = new ClientViewBlockTransform(WormholesClient.instance().session().environment(key).transform());
        return new BlockPos(transform.displayX(destination.getX(), destination.getY(), destination.getZ()),
            transform.displayY(destination.getX(), destination.getY(), destination.getZ()),
            transform.displayZ(destination.getX(), destination.getY(), destination.getZ()));
    }

    static ClientMeshSections.Section section(int key, BlockPos destination) {
        ClientMeshSections.View view = WormholesClient.instance().session().meshes().view(key);
        if (view == null || WormholesClient.instance().session().environment(key) == null) {
            return null;
        }
        return view.section(SectionPos.asLong(display(key, destination)));
    }

    static int cell(int key, BlockPos destination) {
        BlockPos position = display(key, destination);
        return ClientViewProtocol.brickCellIndex(position.getX(), position.getY(), position.getZ());
    }

    static BlockState state(int key, BlockPos destination) {
        ClientMeshSections.Section section = section(key, destination);
        return section == null ? null : section.state(cell(key, destination));
    }

    static int light(int key, BlockPos destination, boolean sky) {
        ClientMeshSections.Section section = section(key, destination);
        return section == null || !section.hasLight() ? -1 : section.light(sky, cell(key, destination));
    }

    static String describe(BlockPos origin, List<BlockPos> samples) {
        WormholesClient client = WormholesClient.instance();
        StringBuilder result = new StringBuilder();
        result.append("native=").append(client.session().nativeSelected()).append(" active=").append(client.session().active());
        result.append(" selected=").append(portalKey(origin)).append(" renderer=").append(ClientPortalRenderer.instance().debugLine());
        for (ClientPortal portal : client.session().portals().values()) {
            int key = portal.portalKey();
            result.append(" portal=").append(key).append(" geometry=").append(portal.geometry());
            result.append(" environment=").append(client.session().environment(key)).append(" sections=").append(sections(key));
            result.append(" available=").append(ClientPortalRenderer.instance().available(key));
            if (client.session().environment(key) == null) {
                continue;
            }
            for (BlockPos position : samples) {
                result.append(" sample=").append(position).append(" display=").append(display(key, position));
                ClientMeshSections.Section section = section(key, position);
                result.append(" state=").append(state(key, position));
                result.append(" tag=").append(section == null ? null : section.blockEntity(cell(key, position)));
            }
        }
        return result.toString();
    }

    static void assertIsolated() {
        if (!WormholesClient.instance().session().nativeSelected() || WormholesClient.instance().tickState().overlay().size() != 0) {
            throw new AssertionError("Native portal rendering mutated the physical-world overlay");
        }
    }

    static List<BlockPos> cells(BlockPos min) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                cells.add(min.offset(x, y, 0));
            }
        }
        return cells;
    }

    static WormholesModRuntime runtime() {
        for (ModInitializer initializer : FabricLoader.getInstance().getEntrypoints("main", ModInitializer.class)) {
            if (initializer instanceof WormholesFabric fabric) {
                try {
                    Field field = WormholesFabric.class.getDeclaredField("runtime");
                    field.setAccessible(true);
                    return (WormholesModRuntime) field.get(fabric);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Wormholes runtime is not reachable", failure);
                }
            }
        }
        throw new IllegalStateException("Wormholes main entrypoint is not loaded");
    }
}
