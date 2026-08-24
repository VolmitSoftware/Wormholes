package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorVisualAnimationBudgetTest
{
	private static final int DOOR_COUNT = 1_000;
	private static final int ATTENDANCE_PASSES =
		DoorPortalAnimation.ATTENDANCE_PERIOD_TICKS / DoorPortalAnimation.FRAME_PERIOD_TICKS;

	@Test
	void oneThousandUnattendedDoorsUseStaggeredChecksWithoutOwnerAdmissions()
	{
		DoorVisualAnimationBudget<UUID> budget = productionBudget();
		List<UUID> doors = doorIds(DOOR_COUNT);
		for(UUID door : doors)
		{
			budget.register(door);
		}

		int checked = 0;
		for(int pass = 0; pass < ATTENDANCE_PASSES; pass++)
		{
			List<DoorVisualAnimationBudget.AttendanceCheck<UUID>> checks = budget.advanceAttendanceChecks();
			assertEquals(DOOR_COUNT / ATTENDANCE_PASSES, checks.size());
			checked += checks.size();
			for(DoorVisualAnimationBudget.AttendanceCheck<UUID> check : checks)
			{
				budget.reportAttendance(check, false);
			}
			assertTrue(budget.acquire().isEmpty());
		}

		assertEquals(DOOR_COUNT, checked);
		assertEquals(0, budget.inFlightCount());
	}

	@Test
	void oneThousandAttendedDoorsRotateWithinTheGlobalCap()
	{
		int admissionLimit = DoorPortalVisualService.MAX_ANIMATION_TASKS_PER_PASS;
		DoorVisualAnimationBudget<UUID> budget = new DoorVisualAnimationBudget<UUID>(new DoorVisualAnimationBudget.Policy(
			admissionLimit,
			DoorPortalVisualService.MAX_ANIMATION_TASKS_IN_FLIGHT,
			1,
			DoorPortalAnimation.FRAME_PERIOD_TICKS));
		List<UUID> doors = doorIds(DOOR_COUNT);
		for(UUID door : doors)
		{
			budget.register(door);
		}
		for(DoorVisualAnimationBudget.AttendanceCheck<UUID> check : budget.advanceAttendanceChecks())
		{
			budget.reportAttendance(check, true);
		}

		Set<UUID> admittedDoors = new HashSet<UUID>();
		int passes = 0;
		while(admittedDoors.size() < doors.size())
		{
			List<DoorVisualAnimationBudget.Admission<UUID>> admissions = budget.acquire();
			assertFalse(admissions.isEmpty());
			assertTrue(admissions.size() <= admissionLimit);
			assertTrue(budget.inFlightCount() <= DoorPortalVisualService.MAX_ANIMATION_TASKS_IN_FLIGHT);
			for(DoorVisualAnimationBudget.Admission<UUID> admission : admissions)
			{
				admittedDoors.add(admission.key());
				budget.complete(admission);
			}
			budget.advanceAttendanceChecks();
			passes++;
		}

		assertEquals((DOOR_COUNT + admissionLimit - 1) / admissionLimit, passes);
	}

	@Test
	void inFlightVisualsReserveCapacityAcrossPasses()
	{
		DoorVisualAnimationBudget<UUID> budget = productionBudget();
		for(UUID door : doorIds(DOOR_COUNT))
		{
			budget.register(door);
		}
		markAllAttended(budget, ATTENDANCE_PASSES);

		List<DoorVisualAnimationBudget.Admission<UUID>> first = budget.acquire();
		assertEquals(DoorPortalVisualService.MAX_ANIMATION_TASKS_IN_FLIGHT, first.size());
		assertTrue(budget.acquire().isEmpty());
		for(int index = 0; index < 10; index++)
		{
			budget.complete(first.get(index));
		}
		List<DoorVisualAnimationBudget.Admission<UUID>> replacements = budget.acquire();

		assertEquals(10, replacements.size());
		assertEquals(DoorPortalVisualService.MAX_ANIMATION_TASKS_IN_FLIGHT, budget.inFlightCount());
	}

	@Test
	void rejectedAndRetiredOwnerTasksReleaseTheirLeasesFairly()
	{
		DoorVisualAnimationBudget<UUID> budget = new DoorVisualAnimationBudget<UUID>(
			new DoorVisualAnimationBudget.Policy(1, 1, 1, 2));
		List<UUID> doors = doorIds(3);
		for(UUID door : doors)
		{
			budget.register(door);
		}
		markAllAttended(budget, 1);

		DoorVisualAnimationBudget.Admission<UUID> rejected = budget.acquire().getFirst();
		budget.reject(rejected);
		DoorVisualAnimationBudget.Admission<UUID> second = budget.acquire().getFirst();
		budget.complete(second);
		DoorVisualAnimationBudget.Admission<UUID> third = budget.acquire().getFirst();
		budget.retire(third.key());
		budget.complete(third);
		DoorVisualAnimationBudget.Admission<UUID> retried = budget.acquire().getFirst();

		assertEquals(doors.get(0), rejected.key());
		assertEquals(doors.get(1), second.key());
		assertEquals(doors.get(2), third.key());
		assertEquals(rejected.key(), retried.key());
	}

	@Test
	void shutdownStopsAdmissionsAndDrainsAcceptedOwnerTasks()
	{
		DoorVisualAnimationBudget<UUID> budget = new DoorVisualAnimationBudget<UUID>(
			new DoorVisualAnimationBudget.Policy(2, 2, 1, 2));
		for(UUID door : doorIds(3))
		{
			budget.register(door);
		}
		markAllAttended(budget, 1);
		List<DoorVisualAnimationBudget.Admission<UUID>> accepted = budget.acquire();

		budget.close();

		assertTrue(budget.acquire().isEmpty());
		assertEquals(2, budget.inFlightCount());
		for(DoorVisualAnimationBudget.Admission<UUID> admission : accepted)
		{
			assertFalse(budget.isActive(admission));
			budget.complete(admission);
		}
		assertEquals(0, budget.inFlightCount());
		assertEquals(0, budget.pendingCount());
	}

	@Test
	void animationTickTracksElapsedGlobalPasses()
	{
		DoorVisualAnimationBudget<UUID> budget = new DoorVisualAnimationBudget<UUID>(
			new DoorVisualAnimationBudget.Policy(1, 1, 10, 2));
		UUID door = UUID.randomUUID();
		budget.register(door);
		DoorVisualAnimationBudget.AttendanceCheck<UUID> check = budget.advanceAttendanceChecks().getFirst();
		budget.reportAttendance(check, true);
		DoorVisualAnimationBudget.Admission<UUID> first = budget.acquire().getFirst();
		assertEquals(0, first.animationTick());
		budget.complete(first);

		budget.advanceAttendanceChecks();
		DoorVisualAnimationBudget.Admission<UUID> second = budget.acquire().getFirst();
		assertEquals(DoorPortalAnimation.FRAME_PERIOD_TICKS, second.animationTick());
	}

	private static DoorVisualAnimationBudget<UUID> productionBudget()
	{
		return new DoorVisualAnimationBudget<UUID>(new DoorVisualAnimationBudget.Policy(
			DoorPortalVisualService.MAX_ANIMATION_TASKS_PER_PASS,
			DoorPortalVisualService.MAX_ANIMATION_TASKS_IN_FLIGHT,
			ATTENDANCE_PASSES,
			DoorPortalAnimation.FRAME_PERIOD_TICKS));
	}

	private static void markAllAttended(DoorVisualAnimationBudget<UUID> budget, int passes)
	{
		for(int pass = 0; pass < passes; pass++)
		{
			for(DoorVisualAnimationBudget.AttendanceCheck<UUID> check : budget.advanceAttendanceChecks())
			{
				budget.reportAttendance(check, true);
			}
		}
	}

	private static List<UUID> doorIds(int count)
	{
		List<UUID> doors = new ArrayList<UUID>(count);
		for(int index = 0; index < count; index++)
		{
			doors.add(new UUID(0L, index + 1L));
		}
		return doors;
	}
}
