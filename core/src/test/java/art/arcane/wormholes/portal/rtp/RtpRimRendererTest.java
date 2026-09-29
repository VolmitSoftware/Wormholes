package art.arcane.wormholes.portal.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

public final class RtpRimRendererTest
{
	private final RtpRimRenderer renderer = new RtpRimRenderer();

	@Test
	public void disabledOrUnattendedRimProducesNoOutput()
	{
		UUID viewerId = uuid("viewer");
		RtpProjectionView ready = view(RtpProjectionView.State.READY, viewerId);

		assertTrue(renderer.calculate(input(viewerId, ready, false, true, RtpRotationMode.STATIC,
				RtpRimRenderer.Phase.READY, 0L, 0L)).isEmpty());
		assertTrue(renderer.calculate(input(viewerId, ready, true, false, RtpRotationMode.STATIC,
				RtpRimRenderer.Phase.READY, 0L, 0L)).isEmpty());
	}

	@Test
	public void rimDoesNotExposeAnotherViewersState()
	{
		UUID ownerId = uuid("owner");
		UUID otherId = uuid("other");
		RtpProjectionView ready = view(RtpProjectionView.State.READY, ownerId);

		assertTrue(renderer.calculate(input(otherId, ready, true, true, RtpRotationMode.STATIC,
				RtpRimRenderer.Phase.READY, 0L, 0L)).isEmpty());
	}

	@Test
	public void noneAndDeniedStatesProduceNoOutput()
	{
		UUID viewerId = uuid("viewer");

		assertTrue(renderer.calculate(input(viewerId, view(RtpProjectionView.State.NONE, viewerId), true, true,
				RtpRotationMode.STATIC, RtpRimRenderer.Phase.READY, 0L, 0L)).isEmpty());
		assertTrue(renderer.calculate(input(viewerId, view(RtpProjectionView.State.DENIED, viewerId), true, true,
				RtpRotationMode.STATIC, RtpRimRenderer.Phase.READY, 0L, 0L)).isEmpty());
	}

	@Test
	public void warmingStateIsYellow()
	{
		UUID viewerId = uuid("viewer");
		RtpRimRenderer.Sample sample = renderer.calculate(input(viewerId, view(RtpProjectionView.State.WARMING, viewerId),
				true, true, RtpRotationMode.STATIC, RtpRimRenderer.Phase.PREPARING, 400L, 0L)).orElseThrow();

		assertEquals(RtpRimRenderer.Color.YELLOW, sample.color());
		assertEquals(0.0D, sample.progress());
	}

	@Test
	public void staticReadyStateIsGreen()
	{
		UUID viewerId = uuid("viewer");
		RtpRimRenderer.Sample sample = renderer.calculate(input(viewerId, view(RtpProjectionView.State.READY, viewerId),
				true, true, RtpRotationMode.STATIC, RtpRimRenderer.Phase.READY, 0L, 0L)).orElseThrow();

		assertEquals(RtpRimRenderer.Color.GREEN, sample.color());
		assertEquals(1.0D, sample.progress());
	}

	@Test
	public void timedReadyStateInterpolatesGreenYellowAndRed()
	{
		UUID viewerId = uuid("viewer");
		RtpProjectionView ready = view(RtpProjectionView.State.READY, viewerId);

		assertTimedSample(viewerId, ready, 0L, RtpRimRenderer.Color.GREEN, 0.0D);
		assertTimedSample(viewerId, ready, 250L, new RtpRimRenderer.Color(128, 255, 0), 0.25D);
		assertTimedSample(viewerId, ready, 500L, RtpRimRenderer.Color.YELLOW, 0.5D);
		assertTimedSample(viewerId, ready, 750L, new RtpRimRenderer.Color(255, 128, 0), 0.75D);
		assertTimedSample(viewerId, ready, 1_000L, RtpRimRenderer.Color.RED, 1.0D);
		assertTimedSample(viewerId, ready, 1_500L, RtpRimRenderer.Color.RED, 1.0D);
	}

	@Test
	public void onTraversalUsesGreenReadyRedClosingAndYellowPreparing()
	{
		UUID viewerId = uuid("viewer");
		RtpProjectionView ready = view(RtpProjectionView.State.READY, viewerId);

		assertEquals(RtpRimRenderer.Color.GREEN, sample(viewerId, ready, RtpRotationMode.ON_TRAVERSAL,
				RtpRimRenderer.Phase.READY).color());
		assertEquals(RtpRimRenderer.Color.RED, sample(viewerId, ready, RtpRotationMode.ON_TRAVERSAL,
				RtpRimRenderer.Phase.CLOSING).color());
		assertEquals(RtpRimRenderer.Color.YELLOW, sample(viewerId, ready, RtpRotationMode.ON_TRAVERSAL,
				RtpRimRenderer.Phase.PREPARING).color());
	}

	@Test
	public void failedStatePulsesRedForTwoSecondsThenStopsRendering()
	{
		UUID viewerId = uuid("viewer");
		RtpProjectionView failed = view(RtpProjectionView.State.FAILED, viewerId);
		RtpRimRenderer.Sample pulse = renderer.calculate(input(viewerId, failed, true, true, RtpRotationMode.STATIC,
				RtpRimRenderer.Phase.CLOSING, 1_999L, 0L)).orElseThrow();
		Optional<RtpRimRenderer.Sample> dark = renderer.calculate(input(viewerId, failed, true, true, RtpRotationMode.STATIC,
				RtpRimRenderer.Phase.CLOSING, 2_000L, 0L));

		assertEquals(RtpRimRenderer.Color.RED, pulse.color());
		assertEquals(1.0D, pulse.progress());
		assertTrue(dark.isEmpty());
	}

	@Test
	public void invalidTimingValuesAreRejected()
	{
		UUID viewerId = uuid("viewer");
		RtpProjectionView ready = view(RtpProjectionView.State.READY, viewerId);

		assertThrows(IllegalArgumentException.class, () -> input(viewerId, ready, true, true, RtpRotationMode.STATIC,
				RtpRimRenderer.Phase.READY, -1L, 0L));
		assertThrows(IllegalArgumentException.class, () -> renderer.calculate(input(viewerId, ready, true, true,
				RtpRotationMode.TIMED, RtpRimRenderer.Phase.READY, 1L, 0L)));
	}

	@Test
	public void unchangedRimDispatchesOncePerInterval()
	{
		UUID viewerId = uuid("viewer");
		UUID portalId = uuid("portal");
		RtpRimRenderer.Input ready = input(viewerId, view(RtpProjectionView.State.READY, viewerId), true, true,
				RtpRotationMode.ON_TRAVERSAL, RtpRimRenderer.Phase.READY, 0L, 0L);

		assertTrue(renderer.nextDispatch(portalId, ready, 100L, 5).isPresent());
		for(long tick = 101L; tick < 105L; tick++)
		{
			assertTrue(renderer.nextDispatch(portalId, ready, tick, 5).isEmpty());
		}
		assertEquals(RtpRimRenderer.Color.GREEN, renderer.nextDispatch(portalId, ready, 105L, 5).orElseThrow().color());
		assertTrue(renderer.nextDispatch(portalId, ready, 106L, 5).isEmpty());
	}

	@Test
	public void phaseChangeDispatchesWithoutWaitingForTheInterval()
	{
		UUID viewerId = uuid("viewer");
		UUID portalId = uuid("portal");
		RtpProjectionView ready = view(RtpProjectionView.State.READY, viewerId);

		assertTrue(renderer.nextDispatch(portalId, input(viewerId, ready, true, true, RtpRotationMode.ON_TRAVERSAL,
				RtpRimRenderer.Phase.READY, 0L, 0L), 10L, 5).isPresent());
		RtpRimRenderer.Sample closing = renderer.nextDispatch(portalId, input(viewerId, ready, true, true,
				RtpRotationMode.ON_TRAVERSAL, RtpRimRenderer.Phase.CLOSING, 0L, 0L), 11L, 5).orElseThrow();

		assertEquals(RtpRimRenderer.Color.RED, closing.color());
	}

	@Test
	public void timedProgressHoldsWithinAStepAndDispatchesWhenItCrossesOne()
	{
		UUID viewerId = uuid("viewer");
		UUID portalId = uuid("portal");
		RtpProjectionView ready = view(RtpProjectionView.State.READY, viewerId);

		assertTrue(renderer.nextDispatch(portalId, input(viewerId, ready, true, true, RtpRotationMode.TIMED,
				RtpRimRenderer.Phase.READY, 0L, 16_000L), 0L, 5).isPresent());
		assertTrue(renderer.nextDispatch(portalId, input(viewerId, ready, true, true, RtpRotationMode.TIMED,
				RtpRimRenderer.Phase.READY, 100L, 16_000L), 1L, 5).isEmpty());
		RtpRimRenderer.Sample stepped = renderer.nextDispatch(portalId, input(viewerId, ready, true, true,
				RtpRotationMode.TIMED, RtpRimRenderer.Phase.READY, 1_000L, 16_000L), 2L, 5).orElseThrow();

		assertEquals(0.0625D, stepped.progress());
	}

	@Test
	public void hiddenRimDispatchesImmediatelyWhenItReturns()
	{
		UUID viewerId = uuid("viewer");
		UUID portalId = uuid("portal");
		RtpProjectionView ready = view(RtpProjectionView.State.READY, viewerId);
		RtpRimRenderer.Input attended = input(viewerId, ready, true, true, RtpRotationMode.STATIC,
				RtpRimRenderer.Phase.READY, 0L, 0L);

		assertTrue(renderer.nextDispatch(portalId, attended, 0L, 5).isPresent());
		assertTrue(renderer.nextDispatch(portalId, input(viewerId, ready, true, false, RtpRotationMode.STATIC,
				RtpRimRenderer.Phase.READY, 0L, 0L), 1L, 5).isEmpty());
		assertTrue(renderer.nextDispatch(portalId, attended, 2L, 5).isPresent());
	}

	@Test
	public void cadenceIsTrackedPerPortalAndViewerAndResetByForgetting()
	{
		UUID viewerId = uuid("viewer");
		UUID otherViewerId = uuid("other-viewer");
		UUID portalId = uuid("portal");
		UUID otherPortalId = uuid("other-portal");
		RtpRimRenderer.Input ready = input(viewerId, view(RtpProjectionView.State.READY, viewerId), true, true,
				RtpRotationMode.STATIC, RtpRimRenderer.Phase.READY, 0L, 0L);
		RtpRimRenderer.Input otherReady = input(otherViewerId, view(RtpProjectionView.State.READY, otherViewerId), true,
				true, RtpRotationMode.STATIC, RtpRimRenderer.Phase.READY, 0L, 0L);

		assertTrue(renderer.nextDispatch(portalId, ready, 0L, 5).isPresent());
		assertTrue(renderer.nextDispatch(otherPortalId, ready, 1L, 5).isPresent());
		assertTrue(renderer.nextDispatch(portalId, otherReady, 1L, 5).isPresent());
		assertTrue(renderer.nextDispatch(otherPortalId, otherReady, 1L, 5).isPresent());
		assertTrue(renderer.nextDispatch(portalId, ready, 1L, 5).isEmpty());

		renderer.forgetPortal(portalId);
		assertTrue(renderer.nextDispatch(portalId, ready, 2L, 5).isPresent());
		assertTrue(renderer.nextDispatch(portalId, otherReady, 2L, 5).isPresent());
		renderer.forgetViewer(viewerId);
		assertTrue(renderer.nextDispatch(portalId, ready, 3L, 5).isPresent());
		assertTrue(renderer.nextDispatch(otherPortalId, ready, 3L, 5).isPresent());
		assertTrue(renderer.nextDispatch(otherPortalId, otherReady, 3L, 5).isEmpty());
	}

	@Test
	public void singleTickIntervalDispatchesEveryTick()
	{
		UUID viewerId = uuid("viewer");
		UUID portalId = uuid("portal");
		RtpRimRenderer.Input ready = input(viewerId, view(RtpProjectionView.State.READY, viewerId), true, true,
				RtpRotationMode.STATIC, RtpRimRenderer.Phase.READY, 0L, 0L);

		for(long tick = 0L; tick < 4L; tick++)
		{
			assertTrue(renderer.nextDispatch(portalId, ready, tick, 1).isPresent());
		}
	}

	private void assertTimedSample(UUID viewerId, RtpProjectionView ready, long elapsedMillis,
			RtpRimRenderer.Color expectedColor, double expectedProgress)
	{
		RtpRimRenderer.Sample sample = renderer.calculate(input(viewerId, ready, true, true, RtpRotationMode.TIMED,
				RtpRimRenderer.Phase.READY, elapsedMillis, 1_000L)).orElseThrow();
		assertEquals(expectedColor, sample.color());
		assertEquals(expectedProgress, sample.progress());
	}

	private RtpRimRenderer.Sample sample(UUID viewerId, RtpProjectionView view, RtpRotationMode rotationMode,
			RtpRimRenderer.Phase phase)
	{
		return renderer.calculate(input(viewerId, view, true, true, rotationMode, phase, 0L, 0L)).orElseThrow();
	}

	private RtpRimRenderer.Input input(UUID viewerId, RtpProjectionView view, boolean enabled, boolean attended,
			RtpRotationMode rotationMode, RtpRimRenderer.Phase phase, long elapsedMillis, long durationMillis)
	{
		return new RtpRimRenderer.Input(viewerId, view, enabled, attended, rotationMode, phase, elapsedMillis, durationMillis);
	}

	private RtpProjectionView view(RtpProjectionView.State state, UUID viewerId)
	{
		return switch(state)
		{
			case NONE -> RtpProjectionView.none(viewerId, 1L);
			case WARMING -> RtpProjectionView.warming(viewerId, 1L);
			case READY -> RtpProjectionView.ready(viewerId, 1L, readyData());
			case DENIED -> RtpProjectionView.denied(viewerId, 1L);
			case FAILED -> RtpProjectionView.failed(viewerId, 1L);
		};
	}

	private RtpProjectionView.ReadyData readyData()
	{
		RtpProjectionView.Point3 sourceCenter = new RtpProjectionView.Point3(0.5D, 65.5D, 0.5D);
		RtpProjectionView.Point3 safeFeet = new RtpProjectionView.Point3(100.5D, 72.0D, -40.5D);
		RtpProjectionView.Vector3 right = new RtpProjectionView.Vector3(1.0D, 0.0D, 0.0D);
		RtpProjectionView.Vector3 up = new RtpProjectionView.Vector3(0.0D, 1.0D, 0.0D);
		RtpProjectionView.Vector3 forward = new RtpProjectionView.Vector3(0.0D, 0.0D, 1.0D);
		RtpProjectionView.SourceFrame sourceFrame = new RtpProjectionView.SourceFrame(
				"minecraft:overworld", sourceCenter, right, up, forward, 3.0D, 4.0D, 1L);
		RtpProjectionView.Target target = new RtpProjectionView.Target(
				"minecraft:overworld", safeFeet, right, up, forward);
		return new RtpProjectionView.ReadyData(uuid("route"), 1L, sourceFrame, target);
	}

	private UUID uuid(String value)
	{
		return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
	}
}
