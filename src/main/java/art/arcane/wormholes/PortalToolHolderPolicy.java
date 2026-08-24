package art.arcane.wormholes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PortalToolHolderPolicy
{
	private static final Comparator<HolderState> OLDEST_FIRST = Comparator
		.comparingLong(HolderState::lastAdmissionOrder)
		.thenComparing(HolderState::playerId);

	private final int fallbackIntervalTicks;
	private final Map<UUID, HolderState> states;
	private long admissionOrder;
	private long leaseSequence;
	private long selectionPass;

	PortalToolHolderPolicy(int fallbackIntervalTicks)
	{
		this.fallbackIntervalTicks = Math.max(1, fallbackIntervalTicks);
		states = new HashMap<UUID, HolderState>();
	}

	synchronized List<Admission> acquireValidations(Collection<UUID> onlinePlayerIds, long currentTick, int maxAdmissions)
	{
		Set<UUID> online = new HashSet<UUID>(onlinePlayerIds);
		states.keySet().removeIf(playerId -> !online.contains(playerId));
		if(online.isEmpty() || maxAdmissions <= 0)
		{
			return List.of();
		}

		ArrayList<HolderState> dirty = new ArrayList<HolderState>();
		ArrayList<HolderState> confirmed = new ArrayList<HolderState>();
		ArrayList<HolderState> fallback = new ArrayList<HolderState>();
		for(UUID playerId : online)
		{
			HolderState state = states.computeIfAbsent(playerId,
				id -> new HolderState(id, initialValidationTick(id, currentTick, fallbackIntervalTicks)));
			if(state.validationInFlight)
			{
				continue;
			}
			if(state.dirty)
			{
				dirty.add(state);
			}
			else if(state.confirmedHolder)
			{
				confirmed.add(state);
			}
			else if(currentTick >= state.nextFallbackTick)
			{
				fallback.add(state);
			}
		}
		dirty.sort(OLDEST_FIRST);
		confirmed.sort(OLDEST_FIRST);
		fallback.sort(OLDEST_FIRST);

		int capacity = Math.min(maxAdmissions, dirty.size() + confirmed.size() + fallback.size());
		if(capacity <= 0)
		{
			return List.of();
		}
		long pass = selectionPass++;
		int priorityTarget = priorityAdmissionTarget(capacity, !dirty.isEmpty() || !confirmed.isEmpty(), !fallback.isEmpty(), pass);
		int fallbackTarget = capacity - priorityTarget;
		int dirtyTarget = priorityAdmissionTarget(priorityTarget, !dirty.isEmpty(), !confirmed.isEmpty(), pass + 1L);
		int confirmedTarget = priorityTarget - dirtyTarget;

		ArrayList<Admission> admissions = new ArrayList<Admission>(capacity);
		int selectedDirty = appendAdmissions(dirty, 0, dirtyTarget, admissions);
		int selectedConfirmed = appendAdmissions(confirmed, 0, confirmedTarget, admissions);
		int selectedFallback = appendAdmissions(fallback, 0, fallbackTarget, admissions);
		int remaining = capacity - admissions.size();
		if(remaining > 0)
		{
			int added = appendAdmissions(dirty, selectedDirty, remaining, admissions);
			selectedDirty += added;
			remaining -= added;
		}
		if(remaining > 0)
		{
			int added = appendAdmissions(confirmed, selectedConfirmed, remaining, admissions);
			selectedConfirmed += added;
			remaining -= added;
		}
		if(remaining > 0)
		{
			appendAdmissions(fallback, selectedFallback, remaining, admissions);
		}
		return admissions;
	}

	synchronized void markDirty(UUID playerId)
	{
		HolderState state = states.computeIfAbsent(playerId, id -> new HolderState(id, Long.MAX_VALUE));
		state.mutationVersion++;
		state.dirty = true;
	}

	synchronized void completeValidation(Admission admission, boolean confirmedHolder, long currentTick)
	{
		HolderState state = matchingState(admission);
		if(state == null)
		{
			return;
		}
		state.confirmedHolder = confirmedHolder;
		state.validationInFlight = false;
		state.dirty = state.mutationVersion != state.validationVersion;
		state.nextFallbackTick = currentTick + fallbackIntervalTicks;
	}

	synchronized void rejectValidation(Admission admission)
	{
		HolderState state = matchingState(admission);
		if(state == null)
		{
			return;
		}
		state.validationInFlight = false;
		state.dirty = true;
		state.lastAdmissionOrder = admission.previousOrder();
	}

	synchronized void remove(UUID playerId)
	{
		states.remove(playerId);
	}

	synchronized void clear()
	{
		states.clear();
	}

	synchronized int size()
	{
		return states.size();
	}

	static long initialValidationTick(UUID playerId, long currentTick, int fallbackIntervalTicks)
	{
		int interval = Math.max(1, fallbackIntervalTicks);
		return currentTick + Math.floorMod(playerId.hashCode(), interval);
	}

	private int appendAdmissions(List<HolderState> source, int start, int requested, List<Admission> destination)
	{
		int selected = Math.min(Math.max(0, requested), source.size() - start);
		for(int i = 0; i < selected; i++)
		{
			HolderState state = source.get(start + i);
			long previousOrder = state.lastAdmissionOrder;
			long token = ++leaseSequence;
			state.validationInFlight = true;
			state.validationVersion = state.mutationVersion;
			state.leaseToken = token;
			state.lastAdmissionOrder = ++admissionOrder;
			destination.add(new Admission(state.playerId, token, previousOrder));
		}
		return selected;
	}

	private HolderState matchingState(Admission admission)
	{
		HolderState state = states.get(admission.playerId());
		return state != null && state.validationInFlight && state.leaseToken == admission.leaseToken() ? state : null;
	}

	private static int priorityAdmissionTarget(int capacity, boolean hasPriority, boolean hasFallback, long pass)
	{
		if(capacity <= 0 || !hasPriority)
		{
			return 0;
		}
		if(!hasFallback)
		{
			return capacity;
		}
		if(capacity == 1)
		{
			return Math.floorMod(pass, 4L) == 3L ? 0 : 1;
		}
		return capacity - Math.max(1, capacity / 4);
	}

	record Admission(UUID playerId, long leaseToken, long previousOrder)
	{
	}

	private static final class HolderState
	{
		private final UUID playerId;
		private boolean confirmedHolder;
		private boolean dirty;
		private boolean validationInFlight;
		private long mutationVersion;
		private long validationVersion;
		private long nextFallbackTick;
		private long lastAdmissionOrder;
		private long leaseToken;

		private HolderState(UUID playerId, long nextFallbackTick)
		{
			this.playerId = playerId;
			this.nextFallbackTick = nextFallbackTick;
		}

		private UUID playerId()
		{
			return playerId;
		}

		private long lastAdmissionOrder()
		{
			return lastAdmissionOrder;
		}
	}
}
