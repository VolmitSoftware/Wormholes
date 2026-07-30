package art.arcane.wormholes;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalToolHolderPolicyTest
{
	@Test
	void unknownPlayersAreStaggeredAcrossTheFallbackWindow()
	{
		PortalToolHolderPolicy policy = new PortalToolHolderPolicy(12);
		UUID first = new UUID(0L, 0L);
		UUID second = new UUID(0L, 11L);

		assertEquals(100L, PortalToolHolderPolicy.initialValidationTick(first, 100L, 12));
		assertEquals(111L, PortalToolHolderPolicy.initialValidationTick(second, 100L, 12));
		assertTrue(policy.acquireValidation(first, 100L));
		assertFalse(policy.acquireValidation(second, 100L));
		assertFalse(policy.acquireValidation(second, 110L));
		assertTrue(policy.acquireValidation(second, 111L));
	}

	@Test
	void confirmedHoldersScheduleEveryScanWhileNonHoldersWaitForFallback()
	{
		PortalToolHolderPolicy policy = new PortalToolHolderPolicy(40);
		UUID playerId = new UUID(0L, 0L);

		assertTrue(policy.acquireValidation(playerId, 0L));
		policy.completeValidation(playerId, true, 0L);
		assertTrue(policy.acquireValidation(playerId, 3L));
		policy.completeValidation(playerId, false, 3L);

		assertFalse(policy.acquireValidation(playerId, 42L));
		assertTrue(policy.acquireValidation(playerId, 43L));
	}

	@Test
	void inventoryEventsMakeNonHoldersEligibleOnTheNextScan()
	{
		PortalToolHolderPolicy policy = new PortalToolHolderPolicy(40);
		UUID playerId = new UUID(0L, 0L);

		assertTrue(policy.acquireValidation(playerId, 0L));
		policy.completeValidation(playerId, false, 0L);
		assertFalse(policy.acquireValidation(playerId, 3L));

		policy.markDirty(playerId);

		assertTrue(policy.acquireValidation(playerId, 3L));
	}

	@Test
	void mutationDuringValidationQueuesAFollowUpValidation()
	{
		PortalToolHolderPolicy policy = new PortalToolHolderPolicy(40);
		UUID playerId = new UUID(0L, 0L);

		policy.markDirty(playerId);
		assertTrue(policy.acquireValidation(playerId, 0L));
		policy.markDirty(playerId);
		policy.completeValidation(playerId, false, 0L);

		assertTrue(policy.acquireValidation(playerId, 3L));
	}

	@Test
	void rejectedSchedulingRetainsImmediateRetryAndCleanupDropsState()
	{
		PortalToolHolderPolicy policy = new PortalToolHolderPolicy(40);
		UUID first = new UUID(0L, 0L);
		UUID second = new UUID(0L, 1L);

		policy.markDirty(first);
		assertTrue(policy.acquireValidation(first, 0L));
		policy.rejectValidation(first);
		assertTrue(policy.acquireValidation(first, 0L));
		policy.remove(first);
		policy.markDirty(second);
		assertEquals(1, policy.size());

		policy.clear();

		assertEquals(0, policy.size());
	}
}
