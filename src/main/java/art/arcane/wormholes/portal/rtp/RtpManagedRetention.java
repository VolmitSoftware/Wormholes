package art.arcane.wormholes.portal.rtp;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class RtpManagedRetention implements RtpService.Retention
{
	private final RtpService.Retention delegate;
	private final AtomicBoolean closed;

	RtpManagedRetention(RtpService.Retention delegate)
	{
		this.delegate = Objects.requireNonNull(delegate, "delegate");
		this.closed = new AtomicBoolean(false);
	}

	static void closeLoaded(RtpService.LoadedCandidate loaded)
	{
		if(loaded != null)
		{
			new RtpManagedRetention(loaded.retention()).close();
		}
	}

	@Override
	public void close()
	{
		if(closed.compareAndSet(false, true))
		{
			delegate.close();
		}
	}
}
