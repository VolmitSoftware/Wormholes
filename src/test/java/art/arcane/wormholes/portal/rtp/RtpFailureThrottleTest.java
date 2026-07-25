package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.Traversive;

public final class RtpFailureThrottleTest
{
	@Test
	public void repeatedFailuresForOneContextAreReportedOncePerWindow()
	{
		RecordingEnvironment environment = new RecordingEnvironment();
		RtpFailureThrottle throttle = new RtpFailureThrottle(environment);

		throttle.report("traversal:one", new IllegalStateException("first"));
		throttle.report("traversal:one", new IllegalStateException("second"));
		environment.nowMillis = 60_000L;
		throttle.report("traversal:one", new IllegalStateException("third"));

		assertEquals(2, environment.reported.size());
		assertEquals("first", environment.reported.get(0).getMessage());
		assertEquals("third", environment.reported.get(1).getMessage());
	}

	@Test
	public void staleContextsAreEvictedInsteadOfAccumulatingForever()
	{
		RecordingEnvironment environment = new RecordingEnvironment();
		RtpFailureThrottle throttle = new RtpFailureThrottle(environment);
		for(int index = 0; index < 512; index++)
		{
			throttle.report("traversal:" + index, new IllegalStateException("churn"));
		}
		assertTrue(throttle.retainedContexts() > 1);

		environment.nowMillis = 60_000L;
		throttle.report("traversal:eviction", new IllegalStateException("late"));

		assertEquals(1, throttle.retainedContexts());
	}

	private static final class RecordingEnvironment implements BukkitRtpRuntime.Environment
	{
		private final List<Throwable> reported = new ArrayList<Throwable>();
		private long nowMillis;

		@Override
		public long nowMillis()
		{
			return nowMillis;
		}

		@Override
		public void sourceRegistered(UUID portalId, Location anchor)
		{
		}

		@Override
		public void sourceUnregistered(UUID portalId)
		{
		}

		@Override
		public World resolveWorld(String worldKey)
		{
			return null;
		}

		@Override
		public CompletionStage<RtpService.LoadedCandidate> loadTraversal(
				RtpService.SearchRequest request,
				RtpValidationRequest.EntityEnvelope envelope)
		{
			throw new UnsupportedOperationException("loadTraversal");
		}

		@Override
		public CompletionStage<RtpSafetyResult> validate(RtpValidationRequest request)
		{
			throw new UnsupportedOperationException("validate");
		}

		@Override
		public CompletionStage<RtpAccessResult> canUse(Player player, RtpDestination destination)
		{
			throw new UnsupportedOperationException("canUse");
		}

		@Override
		public boolean scheduleEntity(Entity entity, Runnable command, Runnable retired)
		{
			return false;
		}

		@Override
		public CompletionStage<Boolean> teleport(Entity entity, Location target)
		{
			throw new UnsupportedOperationException("teleport");
		}

		@Override
		public void completeSuccess(
				LocalPortal portal,
				Entity entity,
				Traversive traversive,
				PortalFrame targetFrame,
				Location target)
		{
		}

		@Override
		public void dispatchRim(LocalPortal portal, Player observer, RtpRimRenderer.Sample sample)
		{
		}

		@Override
		public void worldUnloaded(UUID worldId)
		{
		}

		@Override
		public void reportFailure(String context, Throwable failure)
		{
			reported.add(failure);
		}

		@Override
		public void close()
		{
		}
	}
}
