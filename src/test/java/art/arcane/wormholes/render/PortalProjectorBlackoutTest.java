package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.view.ProjectionWorldView;

public final class PortalProjectorBlackoutTest {
	@Test
	public void buriedCellRequiresSelfAndAllSixNeighborsOccluding() {
		assertFalse(ProjectorSampleMemo.buriedCell(false, new boolean[] { true, true, true, true, true, true }));
		assertTrue(ProjectorSampleMemo.buriedCell(true, new boolean[] { true, true, true, true, true, true }));
		for (int i = 0; i < 6; i++) {
			boolean[] neighbors = new boolean[] { true, true, true, true, true, true };
			neighbors[i] = false;
			assertFalse(ProjectorSampleMemo.buriedCell(true, neighbors), "exposed neighbor " + i + " must leave the cell un-buried");
		}
	}

	@Test
	public void occludingSampleTreatsUnknownAndAirNeighborsAsNonOccluding() {
		FakeWorldView view = new FakeWorldView();
		view.put(0, 0, 0, blockData(Material.AIR));
		assertFalse(ProjectorSampleMemo.occludingSample(view, 0, 0, 0));
		assertFalse(ProjectorSampleMemo.occludingSample(view, 1, 0, 0));
	}

	@Test
	public void buriedInViewLeavesAirAndUnknownSelfExposed() {
		FakeWorldView view = new FakeWorldView();
		boolean[] scratch = new boolean[6];
		assertFalse(ProjectorSampleMemo.buriedInView(view, 0, 0, 0, blockData(Material.AIR), scratch));
		assertFalse(ProjectorSampleMemo.buriedInView(view, 0, 0, 0, null, scratch));
	}

	private static BlockData blockData(Material material) {
		return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class },
			(proxy, method, args) -> switch (method.getName()) {
				case "getMaterial" -> material;
				case "toString", "getAsString" -> String.valueOf(material);
				case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
				case "equals" -> Boolean.valueOf(proxy == args[0]);
				case "clone" -> proxy;
				default -> null;
			});
	}

	private static final class FakeWorldView implements ProjectionWorldView {
		private final Map<String, BlockData> blocks = new HashMap<String, BlockData>();

		private void put(int x, int y, int z, BlockData data) {
			blocks.put(key(x, y, z), data);
		}

		@Override
		public World getWorld() {
			return null;
		}

		@Override
		public int getMinHeight() {
			return -64;
		}

		@Override
		public int getMaxHeight() {
			return 320;
		}

		@Override
		public BlockData sampleBlockData(int x, int y, int z) {
			return blocks.get(key(x, y, z));
		}

		@Override
		public String sampleBiome(int x, int y, int z) {
			return null;
		}

		@Override
		public int getLight(int x, int y, int z) {
			return ProjectionWorldView.LIGHT_UNAVAILABLE;
		}

		@Override
		public int getSkyDarken() {
			return 0;
		}

		private static String key(int x, int y, int z) {
			return x + ":" + y + ":" + z;
		}
	}
}
