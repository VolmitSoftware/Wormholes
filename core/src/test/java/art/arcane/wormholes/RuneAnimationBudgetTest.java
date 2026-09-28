package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuneAnimationBudgetTest
{
	@Test
	void thousandPlayersStayHardCappedAndCompleteOneFairRotation()
	{
		RuneAnimationBudget budget = new RuneAnimationBudget(64);
		List<UUID> players = playerIds(1_000);
		Set<UUID> seen = new HashSet<UUID>();

		for(int pass = 0; pass < 16; pass++)
		{
			List<RuneAnimationBudget.Admission> admissions = budget.acquire(players);
			assertEquals(64, admissions.size());
			for(RuneAnimationBudget.Admission admission : admissions)
			{
				seen.add(admission.playerId());
				budget.complete(admission);
			}
		}

		assertEquals(1_000, seen.size());
	}

	@Test
	void inFlightPlayersAreNotResubmittedAndOtherPlayersKeepAdvancing()
	{
		RuneAnimationBudget budget = new RuneAnimationBudget(2);
		List<UUID> players = playerIds(5);
		List<RuneAnimationBudget.Admission> first = budget.acquire(players);
		List<RuneAnimationBudget.Admission> second = budget.acquire(players);

		assertEquals(2, first.size());
		assertEquals(2, second.size());
		assertTrue(first.stream().noneMatch(admission -> second.stream()
			.anyMatch(other -> other.playerId().equals(admission.playerId()))));
		assertEquals(1, budget.acquire(players).size());
	}

	@Test
	void rejectedLeaseRetriesAndStaleCompletionCannotReleaseItsReplacement()
	{
		RuneAnimationBudget budget = new RuneAnimationBudget(1);
		UUID playerId = new UUID(0L, 1L);
		RuneAnimationBudget.Admission rejected = budget.acquire(List.of(playerId)).getFirst();
		budget.reject(rejected);
		RuneAnimationBudget.Admission retry = budget.acquire(List.of(playerId)).getFirst();

		budget.complete(rejected);
		assertTrue(budget.acquire(List.of(playerId)).isEmpty());
		budget.complete(retry);
		assertEquals(playerId, budget.acquire(List.of(playerId)).getFirst().playerId());
	}

	@Test
	void onlineSnapshotAndLifecycleCleanupDropStaleState()
	{
		RuneAnimationBudget budget = new RuneAnimationBudget(2);
		UUID first = new UUID(0L, 1L);
		UUID second = new UUID(0L, 2L);
		budget.acquire(List.of(first, second));

		budget.acquire(List.of(second));
		assertEquals(1, budget.size());
		budget.remove(second);
		assertEquals(0, budget.size());
		budget.acquire(List.of(first));
		budget.clear();
		assertEquals(0, budget.size());
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
