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

final class RuneAnimationBudget
{
	private static final Comparator<PlayerState> OLDEST_FIRST = Comparator
		.comparingLong(PlayerState::lastAdmissionOrder)
		.thenComparing(PlayerState::playerId);

	private final int maxAdmissionsPerPass;
	private final Map<UUID, PlayerState> states;
	private long admissionOrder;
	private long leaseSequence;

	RuneAnimationBudget(int maxAdmissionsPerPass)
	{
		this.maxAdmissionsPerPass = Math.max(1, maxAdmissionsPerPass);
		states = new HashMap<UUID, PlayerState>();
	}

	synchronized List<Admission> acquire(Collection<UUID> onlinePlayerIds)
	{
		Set<UUID> online = new HashSet<UUID>(onlinePlayerIds);
		states.keySet().removeIf(playerId -> !online.contains(playerId));
		ArrayList<PlayerState> eligible = new ArrayList<PlayerState>(online.size());
		for(UUID playerId : online)
		{
			PlayerState state = states.computeIfAbsent(playerId, PlayerState::new);
			if(!state.inFlight)
			{
				eligible.add(state);
			}
		}
		eligible.sort(OLDEST_FIRST);
		int selected = Math.min(maxAdmissionsPerPass, eligible.size());
		ArrayList<Admission> admissions = new ArrayList<Admission>(selected);
		for(int i = 0; i < selected; i++)
		{
			PlayerState state = eligible.get(i);
			long previousOrder = state.lastAdmissionOrder;
			long token = ++leaseSequence;
			state.inFlight = true;
			state.leaseToken = token;
			state.lastAdmissionOrder = ++admissionOrder;
			admissions.add(new Admission(state.playerId, token, previousOrder));
		}
		return admissions;
	}

	synchronized void complete(Admission admission)
	{
		PlayerState state = matchingState(admission);
		if(state != null)
		{
			state.inFlight = false;
		}
	}

	synchronized void reject(Admission admission)
	{
		PlayerState state = matchingState(admission);
		if(state != null)
		{
			state.inFlight = false;
			state.lastAdmissionOrder = admission.previousOrder();
		}
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

	private PlayerState matchingState(Admission admission)
	{
		PlayerState state = states.get(admission.playerId());
		return state != null && state.inFlight && state.leaseToken == admission.leaseToken() ? state : null;
	}

	record Admission(UUID playerId, long leaseToken, long previousOrder)
	{
	}

	private static final class PlayerState
	{
		private final UUID playerId;
		private boolean inFlight;
		private long lastAdmissionOrder;
		private long leaseToken;

		private PlayerState(UUID playerId)
		{
			this.playerId = playerId;
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
