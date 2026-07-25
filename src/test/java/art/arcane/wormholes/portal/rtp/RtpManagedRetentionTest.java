package art.arcane.wormholes.portal.rtp;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class RtpManagedRetentionTest
{
	@Test
	public void repeatedClosesReleaseTheDelegateExactlyOnce()
	{
		AtomicInteger releases = new AtomicInteger();
		RtpManagedRetention retention = new RtpManagedRetention(() -> releases.incrementAndGet());

		retention.close();
		retention.close();
		retention.close();

		assertEquals(1, releases.get());
	}

	@Test
	public void closingALoadedCandidateReleasesItsRetention()
	{
		AtomicInteger releases = new AtomicInteger();
		RtpService.LoadedCandidate loaded = new RtpService.LoadedCandidate(
				validationRequest(),
				() -> releases.incrementAndGet());

		RtpManagedRetention.closeLoaded(loaded);

		assertEquals(1, releases.get());
	}

	@Test
	public void closingAnAbsentCandidateIsANoOp()
	{
		RtpManagedRetention.closeLoaded(null);
	}

	private static RtpValidationRequest validationRequest()
	{
		RtpDestination destination = new RtpDestination("world", 0, 64, 0, 1L, 0);
		RtpValidationRequest.RegionSnapshot region = new RtpValidationRequest.RegionSnapshot(
				"region-0-0",
				0,
				0,
				List.of(
						RtpValidationRequest.BlockSnapshot.solid(0, 63, 0, "minecraft:stone"),
						RtpValidationRequest.BlockSnapshot.air(0, 64, 0),
						RtpValidationRequest.BlockSnapshot.air(0, 65, 0)));
		return RtpValidationRequest.builder(destination)
				.worldBounds(-64, 320)
				.worldBorder(new RtpValidationRequest.WorldBorder(-30_000_000.0D, -30_000_000.0D, 30_000_000.0D, 30_000_000.0D))
				.regionSnapshots(List.of(region))
				.build();
	}
}
