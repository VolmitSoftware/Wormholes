package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
		PortalToolHolderPolicy.Admission firstAdmission = only(policy.acquireValidations(List.of(first, second), 100L, 2));
		policy.completeValidation(firstAdmission, false, 100L);
		assertTrue(policy.acquireValidations(List.of(first, second), 110L, 2).isEmpty());
		assertEquals(second, only(policy.acquireValidations(List.of(first, second), 111L, 2)).playerId());
	}

	@Test
	void confirmedHoldersScheduleEveryScanWhileNonHoldersWaitForFallback()
	{
		PortalToolHolderPolicy policy = new PortalToolHolderPolicy(40);
		UUID playerId = new UUID(0L, 0L);

		PortalToolHolderPolicy.Admission initial = only(policy.acquireValidations(List.of(playerId), 0L, 1));
		policy.completeValidation(initial, true, 0L);
		PortalToolHolderPolicy.Admission confirmed = only(policy.acquireValidations(List.of(playerId), 3L, 1));
		policy.completeValidation(confirmed, false, 3L);

		assertTrue(policy.acquireValidations(List.of(playerId), 42L, 1).isEmpty());
		assertEquals(playerId, only(policy.acquireValidations(List.of(playerId), 43L, 1)).playerId());
	}

	@Test
	void inventoryEventsMakeNonHoldersEligibleOnTheNextScan()
	{
		PortalToolHolderPolicy policy = new PortalToolHolderPolicy(40);
		UUID playerId = new UUID(0L, 0L);

		PortalToolHolderPolicy.Admission initial = only(policy.acquireValidations(List.of(playerId), 0L, 1));
		policy.completeValidation(initial, false, 0L);
		assertTrue(policy.acquireValidations(List.of(playerId), 3L, 1).isEmpty());

		policy.markDirty(playerId);

		assertEquals(playerId, only(policy.acquireValidations(List.of(playerId), 3L, 1)).playerId());
	}

	@Test
	void mutationDuringValidationQueuesAFollowUpValidation()
	{
		PortalToolHolderPolicy policy = new PortalToolHolderPolicy(40);
		UUID playerId = new UUID(0L, 0L);

		policy.markDirty(playerId);
		PortalToolHolderPolicy.Admission admission = only(policy.acquireValidations(List.of(playerId), 0L, 1));
		policy.markDirty(playerId);
		policy.completeValidation(admission, false, 0L);

		assertEquals(playerId, only(policy.acquireValidations(List.of(playerId), 3L, 1)).playerId());
	}

	@Test
	void rejectedSchedulingRetainsImmediateRetryAndStaleCompletionCannotReleaseTheRetry()
	{
		PortalToolHolderPolicy policy = new PortalToolHolderPolicy(40);
		UUID playerId = new UUID(0L, 0L);

		policy.markDirty(playerId);
		PortalToolHolderPolicy.Admission rejected = only(policy.acquireValidations(List.of(playerId), 0L, 1));
		policy.rejectValidation(rejected);
		PortalToolHolderPolicy.Admission retry = only(policy.acquireValidations(List.of(playerId), 0L, 1));
		policy.completeValidation(rejected, false, 0L);

		assertTrue(policy.acquireValidations(List.of(playerId), 0L, 1).isEmpty());
		policy.completeValidation(retry, false, 0L);
	}

	@Test
	void thousandConfirmedHoldersStayHardCappedAndCompleteOneFairRotation()
	{
		PortalToolHolderPolicy policy = new PortalToolHolderPolicy(40);
		List<UUID> players = playerIds(1_000);
		for(UUID playerId : players)
		{
			policy.markDirty(playerId);
		}
		for(PortalToolHolderPolicy.Admission admission : policy.acquireValidations(players, 0L, 1_000))
		{
			policy.completeValidation(admission, true, 0L);
		}

		Set<UUID> seen = new HashSet<UUID>();
		for(int pass = 0; pass < 16; pass++)
		{
			List<PortalToolHolderPolicy.Admission> admissions = policy.acquireValidations(players, 3L + (pass * 3L), 64);
			assertEquals(64, admissions.size());
			for(PortalToolHolderPolicy.Admission admission : admissions)
			{
				seen.add(admission.playerId());
				policy.completeValidation(admission, true, 3L + (pass * 3L));
			}
		}

		assertEquals(1_000, seen.size());
	}

	@Test
	void dirtyAndConfirmedPlayersLeadWhileFallbackValidationCannotStarve()
	{
		PortalToolHolderPolicy policy = new PortalToolHolderPolicy(40);
		List<UUID> players = playerIds(1_000);
		for(UUID playerId : players)
		{
			policy.markDirty(playerId);
		}
		for(PortalToolHolderPolicy.Admission admission : policy.acquireValidations(players, 0L, 1_000))
		{
			int player = (int) admission.playerId().getLeastSignificantBits();
			policy.completeValidation(admission, player >= 600 && player < 900, 0L);
		}
		for(int player = 0; player < 600; player++)
		{
			policy.markDirty(players.get(player));
		}

		List<PortalToolHolderPolicy.Admission> admissions = policy.acquireValidations(players, 40L, 64);
		int dirty = 0;
		int confirmed = 0;
		int fallback = 0;
		for(PortalToolHolderPolicy.Admission admission : admissions)
		{
			long player = admission.playerId().getLeastSignificantBits();
			if(player < 600L)
			{
				dirty++;
			}
			else if(player < 900L)
			{
				confirmed++;
			}
			else
			{
				fallback++;
			}
		}

		assertEquals(64, admissions.size());
		assertEquals(36, dirty);
		assertEquals(12, confirmed);
		assertEquals(16, fallback);
	}

	@Test
	void onlineSnapshotAndLifecycleCleanupDropStaleState()
	{
		PortalToolHolderPolicy policy = new PortalToolHolderPolicy(40);
		UUID first = new UUID(0L, 0L);
		UUID second = new UUID(0L, 1L);
		policy.markDirty(first);
		policy.markDirty(second);
		policy.acquireValidations(List.of(first, second), 0L, 2);

		policy.acquireValidations(List.of(second), 3L, 2);
		assertEquals(1, policy.size());
		policy.remove(second);
		assertEquals(0, policy.size());
		policy.markDirty(first);
		policy.clear();
		assertEquals(0, policy.size());
	}

	private static PortalToolHolderPolicy.Admission only(List<PortalToolHolderPolicy.Admission> admissions)
	{
		assertEquals(1, admissions.size());
		return admissions.getFirst();
	}

	private static List<UUID> playerIds(int count)
	{
		ArrayList<UUID> players = new ArrayList<UUID>(count);
		for(int player = 0; player < count; player++)
		{
			players.add(new UUID(0L, player));
		}
		return players;
	}
}
