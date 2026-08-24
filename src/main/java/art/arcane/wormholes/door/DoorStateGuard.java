package art.arcane.wormholes.door;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class DoorStateGuard
{
	private final AtomicBoolean started;
	private final AtomicBoolean closed;
	private final AtomicBoolean acceptingEntries;
	private final Set<UUID> quarantinedPockets;
	private final Object lifecycleLock;

	private volatile DoorStateService state;

	DoorStateGuard()
	{
		started = new AtomicBoolean();
		closed = new AtomicBoolean();
		acceptingEntries = new AtomicBoolean(true);
		quarantinedPockets = ConcurrentHashMap.newKeySet();
		lifecycleLock = new Object();
	}

	boolean beginStart()
	{
		return started.compareAndSet(false, true);
	}

	DoorStateService open(Path pluginDataDirectory) throws IOException
	{
		DoorStateService opened = DoorStateService.under(pluginDataDirectory);
		state = opened;
		return opened;
	}

	DoorStateService state()
	{
		DoorStateService active = state;
		if(active == null)
		{
			throw new IllegalStateException("Dimensional Doors are not started");
		}
		return active;
	}

	boolean closed()
	{
		return closed.get();
	}

	boolean acceptingEntries()
	{
		return acceptingEntries.get();
	}

	void quarantinePocket(UUID spaceId)
	{
		quarantinedPockets.add(Objects.requireNonNull(spaceId, "spaceId"));
	}

	void restorePocket(UUID spaceId)
	{
		quarantinedPockets.remove(Objects.requireNonNull(spaceId, "spaceId"));
	}

	boolean pocketQuarantined(UUID spaceId)
	{
		return quarantinedPockets.contains(Objects.requireNonNull(spaceId, "spaceId"));
	}

	boolean beginDrain()
	{
		return acceptingEntries.getAndSet(false);
	}

	void resumeEntries()
	{
		if(!closed.get())
		{
			acceptingEntries.set(true);
		}
	}

	boolean markClosed()
	{
		synchronized(lifecycleLock)
		{
			if(!closed.compareAndSet(false, true))
			{
				return false;
			}
		}
		acceptingEntries.set(false);
		return true;
	}

	<T> T mutate(IOCall<T> mutation) throws IOException
	{
		synchronized(lifecycleLock)
		{
			if(closed.get())
			{
				throw new IOException("Dimensional Doors are shutting down");
			}
			return mutation.call();
		}
	}

	@FunctionalInterface
	interface IOCall<T>
	{
		T call() throws IOException;
	}
}
