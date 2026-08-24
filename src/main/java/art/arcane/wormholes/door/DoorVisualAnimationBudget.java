package art.arcane.wormholes.door;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DoorVisualAnimationBudget<K>
{
	private final int maxAdmissionsPerPass;
	private final int maxInFlight;
	private final int attendancePeriodPasses;
	private final int framePeriodTicks;
	private final Map<K, VisualState<K>> states;
	private final List<ArrayDeque<VisualState<K>>> attendanceBuckets;
	private final ArrayDeque<VisualState<K>> ready;
	private long pass;
	private long registrationSequence;
	private long attendanceSequence;
	private long leaseSequence;
	private int inFlight;
	private boolean closed;

	DoorVisualAnimationBudget(Policy policy)
	{
		Objects.requireNonNull(policy, "policy");
		int maxAdmissionsPerPass = policy.maxAdmissionsPerPass();
		int maxInFlight = policy.maxInFlight();
		int attendancePeriodPasses = policy.attendancePeriodPasses();
		int framePeriodTicks = policy.framePeriodTicks();
		if(maxAdmissionsPerPass <= 0)
		{
			throw new IllegalArgumentException("maxAdmissionsPerPass must be positive");
		}
		if(maxInFlight <= 0)
		{
			throw new IllegalArgumentException("maxInFlight must be positive");
		}
		if(attendancePeriodPasses <= 0)
		{
			throw new IllegalArgumentException("attendancePeriodPasses must be positive");
		}
		if(framePeriodTicks <= 0)
		{
			throw new IllegalArgumentException("framePeriodTicks must be positive");
		}
		this.maxAdmissionsPerPass = maxAdmissionsPerPass;
		this.maxInFlight = maxInFlight;
		this.attendancePeriodPasses = attendancePeriodPasses;
		this.framePeriodTicks = framePeriodTicks;
		states = new HashMap<K, VisualState<K>>();
		attendanceBuckets = new ArrayList<ArrayDeque<VisualState<K>>>(attendancePeriodPasses);
		for(int index = 0; index < attendancePeriodPasses; index++)
		{
			attendanceBuckets.add(new ArrayDeque<VisualState<K>>());
		}
		ready = new ArrayDeque<VisualState<K>>();
	}

	synchronized void register(K key)
	{
		Objects.requireNonNull(key, "key");
		if(closed || states.containsKey(key))
		{
			return;
		}
		int attendanceSlot = (int) Math.floorMod(
			pass + registrationSequence++, (long) attendancePeriodPasses);
		VisualState<K> state = new VisualState<K>(key, pass, attendanceSlot);
		states.put(key, state);
		attendanceBuckets.get(attendanceSlot).addLast(state);
	}

	synchronized List<AttendanceCheck<K>> advanceAttendanceChecks()
	{
		if(closed)
		{
			return List.of();
		}
		long currentPass = pass++;
		int slot = (int) Math.floorMod(currentPass, (long) attendancePeriodPasses);
		ArrayDeque<VisualState<K>> bucket = attendanceBuckets.get(slot);
		List<AttendanceCheck<K>> checks = new ArrayList<AttendanceCheck<K>>(bucket.size());
		for(VisualState<K> state : bucket)
		{
			if(state.retired || states.get(state.key) != state)
			{
				continue;
			}
			long token = ++attendanceSequence;
			state.attendanceToken = token;
			checks.add(new AttendanceCheck<K>(state.key, token));
		}
		return List.copyOf(checks);
	}

	synchronized void reportAttendance(AttendanceCheck<K> check, boolean attended)
	{
		VisualState<K> state = matchingAttendance(check);
		if(state == null)
		{
			return;
		}
		state.attended = attended;
		if(attended && !state.inFlight && !state.readyQueued)
		{
			state.readyQueued = true;
			ready.addLast(state);
		}
	}

	synchronized List<Admission<K>> acquire()
	{
		int capacity = Math.min(maxAdmissionsPerPass, maxInFlight - inFlight);
		if(closed || capacity <= 0)
		{
			return List.of();
		}
		long currentPass = Math.max(0L, pass - 1L);
		List<Admission<K>> admissions = new ArrayList<Admission<K>>(capacity);
		while(admissions.size() < capacity)
		{
			VisualState<K> state = ready.pollFirst();
			if(state == null)
			{
				break;
			}
			state.readyQueued = false;
			if(state.retired || state.inFlight || !state.attended || states.get(state.key) != state)
			{
				continue;
			}
			long leaseToken = ++leaseSequence;
			long elapsedPasses = Math.max(0L, currentPass - state.registeredPass);
			state.inFlight = true;
			state.leaseToken = leaseToken;
			inFlight++;
			admissions.add(new Admission<K>(state.key, leaseToken, (int) (elapsedPasses * framePeriodTicks)));
		}
		return List.copyOf(admissions);
	}

	synchronized boolean isActive(Admission<K> admission)
	{
		VisualState<K> state = matchingAdmission(admission);
		return state != null && !closed && !state.retired && state.attended;
	}

	synchronized void complete(Admission<K> admission)
	{
		VisualState<K> state = matchingAdmission(admission);
		if(state == null)
		{
			return;
		}
		state.inFlight = false;
		inFlight--;
		if(state.retired || closed)
		{
			states.remove(state.key, state);
			return;
		}
		if(state.attended && !state.readyQueued)
		{
			state.readyQueued = true;
			ready.addLast(state);
		}
	}

	synchronized void reject(Admission<K> admission)
	{
		complete(admission);
	}

	synchronized void retire(K key)
	{
		VisualState<K> state = states.get(key);
		if(state == null)
		{
			return;
		}
		state.retired = true;
		state.attended = false;
		if(state.readyQueued)
		{
			ready.remove(state);
			state.readyQueued = false;
		}
		attendanceBuckets.get(state.attendanceSlot).remove(state);
		if(!state.inFlight)
		{
			states.remove(key, state);
		}
	}

	synchronized void close()
	{
		closed = true;
		ready.clear();
		for(ArrayDeque<VisualState<K>> bucket : attendanceBuckets)
		{
			bucket.clear();
		}
		List<VisualState<K>> retired = new ArrayList<VisualState<K>>(states.values());
		for(VisualState<K> state : retired)
		{
			state.retired = true;
			state.attended = false;
			state.readyQueued = false;
			if(!state.inFlight)
			{
				states.remove(state.key, state);
			}
		}
	}

	synchronized int pendingCount()
	{
		int pending = 0;
		for(VisualState<K> state : states.values())
		{
			if(state.readyQueued)
			{
				pending++;
			}
		}
		return pending;
	}

	synchronized int inFlightCount()
	{
		return inFlight;
	}

	private VisualState<K> matchingAttendance(AttendanceCheck<K> check)
	{
		if(check == null)
		{
			return null;
		}
		VisualState<K> state = states.get(check.key());
		return state != null && !state.retired && state.attendanceToken == check.token()
			? state
			: null;
	}

	private VisualState<K> matchingAdmission(Admission<K> admission)
	{
		if(admission == null)
		{
			return null;
		}
		VisualState<K> state = states.get(admission.key());
		return state != null && state.inFlight && state.leaseToken == admission.leaseToken()
			? state
			: null;
	}

	record AttendanceCheck<K>(K key, long token)
	{
	}

	record Admission<K>(K key, long leaseToken, int animationTick)
	{
	}

	record Policy(int maxAdmissionsPerPass, int maxInFlight, int attendancePeriodPasses, int framePeriodTicks)
	{
	}

	private static final class VisualState<K>
	{
		private final K key;
		private final long registeredPass;
		private final int attendanceSlot;
		private boolean attended;
		private boolean readyQueued;
		private boolean inFlight;
		private boolean retired;
		private long attendanceToken;
		private long leaseToken;

		private VisualState(K key, long registeredPass, int attendanceSlot)
		{
			this.key = key;
			this.registeredPass = registeredPass;
			this.attendanceSlot = attendanceSlot;
		}
	}
}
