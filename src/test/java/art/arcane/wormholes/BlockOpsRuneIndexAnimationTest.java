package art.arcane.wormholes;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class BlockOpsRuneIndexAnimationTest
{
	@Test
	void emptyRuneIndexDoesNotInspectOnlinePlayers()
	{
		BlockOpsRuneIndex index = new BlockOpsRuneIndex();
		boolean[] inspected = new boolean[] { false };

		index.updatePlacedBlocks(() ->
		{
			inspected[0] = true;
			return List.of();
		});

		assertFalse(inspected[0]);
	}
}
