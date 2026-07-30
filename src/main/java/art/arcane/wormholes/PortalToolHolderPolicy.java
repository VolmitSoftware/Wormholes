package art.arcane.wormholes;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PortalToolHolderPolicy
{
	private final int fallbackIntervalTicks;
	private final ConcurrentHashMap<UUID, HolderState> states;

	PortalToolHolderPolicy(int fallbackIntervalTicks)
	{
		this.fallbackIntervalTicks = Math.max(1, fallbackIntervalTicks);
		this.states = new ConcurrentHashMap<UUID, HolderState>();
	}

	boolean acquireValidation(UUID playerId, long currentTick)
	{
		HolderState state = states.computeIfAbsent(playerId,
			id -> new HolderState(initialValidationTick(id, currentTick, fallbackIntervalTicks)));
		synchronized(state)
		{
			if(state.validationInFlight)
			{
				return false;
			}
			if(!state.confirmedHolder && !state.dirty && currentTick < state.nextFallbackTick)
			{
				return false;
			}
			state.validationInFlight = true;
			state.validationVersion = state.mutationVersion;
			return true;
		}
	}

	void markDirty(UUID playerId)
	{
		HolderState state = states.computeIfAbsent(playerId, id -> new HolderState(Long.MAX_VALUE));
		synchronized(state)
		{
			state.mutationVersion++;
			state.dirty = true;
		}
	}

	void completeValidation(UUID playerId, boolean confirmedHolder, long currentTick)
	{
		HolderState state = states.get(playerId);
		if(state == null)
		{
			return;
		}
		synchronized(state)
		{
			state.confirmedHolder = confirmedHolder;
			state.validationInFlight = false;
			state.dirty = state.mutationVersion != state.validationVersion;
			state.nextFallbackTick = currentTick + fallbackIntervalTicks;
		}
	}

	void rejectValidation(UUID playerId)
	{
		HolderState state = states.get(playerId);
		if(state == null)
		{
			return;
		}
		synchronized(state)
		{
			if(!state.validationInFlight)
			{
				return;
			}
			state.validationInFlight = false;
			state.dirty = true;
		}
	}

	void remove(UUID playerId)
	{
		states.remove(playerId);
	}

	void clear()
	{
		states.clear();
	}

	int size()
	{
		return states.size();
	}

	static long initialValidationTick(UUID playerId, long currentTick, int fallbackIntervalTicks)
	{
		int interval = Math.max(1, fallbackIntervalTicks);
		return currentTick + Math.floorMod(playerId.hashCode(), interval);
	}

	private static final class HolderState
	{
		private boolean confirmedHolder;
		private boolean dirty;
		private boolean validationInFlight;
		private long mutationVersion;
		private long validationVersion;
		private long nextFallbackTick;

		private HolderState(long nextFallbackTick)
		{
			this.nextFallbackTick = nextFallbackTick;
		}
	}
}
