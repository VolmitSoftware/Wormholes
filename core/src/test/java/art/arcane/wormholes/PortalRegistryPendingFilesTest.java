package art.arcane.wormholes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

public final class PortalRegistryPendingFilesTest
{
	@Test
	public void duplicateQueuesAreIgnoredAndResolvedFilesCanBeQueuedAgain()
	{
		PortalRegistryPendingFiles pending = new PortalRegistryPendingFiles();
		File first = new File("first.json");
		File second = new File("second.json");

		assertTrue(pending.add(first));
		assertFalse(pending.add(first));
		assertTrue(pending.add(second));
		assertEquals(2, pending.size());

		pending.resolve(file -> file.equals(first));

		assertEquals(1, pending.size());
		assertFalse(pending.isEmpty());
		assertTrue(pending.add(first));
		assertFalse(pending.add(first));
		assertEquals(2, pending.size());

		pending.clear();

		assertTrue(pending.isEmpty());
		assertEquals(0, pending.size());
	}

	@Test
	public void adminClearDuringAPendingLoadWalkNeverBreaksTheIterator() throws InterruptedException
	{
		PortalRegistryPendingFiles pending = new PortalRegistryPendingFiles();
		List<File> files = new ArrayList<File>();
		for(int i = 0; i < 128; i++)
		{
			files.add(new File("portal-" + i + ".json"));
		}

		AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		AtomicInteger walks = new AtomicInteger();
		AtomicBoolean loading = new AtomicBoolean(true);
		CountDownLatch start = new CountDownLatch(1);

		Thread loader = new Thread(() ->
		{
			try
			{
				start.await();
				for(int pass = 0; pass < 200; pass++)
				{
					for(File file : files)
					{
						pending.add(file);
					}
					pending.resolve(file -> file.getName().isEmpty());
					walks.incrementAndGet();
				}
			}
			catch(Throwable e)
			{
				failure.compareAndSet(null, e);
			}
			finally
			{
				loading.set(false);
			}
		});

		Thread admin = new Thread(() ->
		{
			try
			{
				start.await();
				while(loading.get())
				{
					pending.clear();
				}
			}
			catch(Throwable e)
			{
				failure.compareAndSet(null, e);
			}
		});

		loader.start();
		admin.start();
		start.countDown();
		loader.join(60000L);
		admin.join(60000L);

		assertNull(failure.get(), () -> "pending portal queue failed under a concurrent delete: " + failure.get());
		assertEquals(200, walks.get());
	}
}
