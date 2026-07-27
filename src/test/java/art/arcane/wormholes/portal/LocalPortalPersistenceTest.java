package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalPortalPersistenceTest
{
	@TempDir
	Path temporaryDirectory;

	@Test
	void preparedSaveUsesStableEncodedStateAndRetainsNewerDirtyGeneration() throws Exception
	{
		World world = LocalPortalTestSupport.world("persistence");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		Path stateFile = temporaryDirectory.resolve("portal.json");
		LocalPortalPersistence persistence = new LocalPortalPersistence(portal, ignored -> stateFile.toFile());
		portal.setName("Before");
		persistence.save();

		PortalSaveSnapshot prepared = persistence.prepareSave();
		assertNotNull(prepared);
		portal.setName("After");
		persistence.save();
		persistence.writeSave(prepared);

		String encoded = Files.readString(stateFile);
		assertTrue(encoded.contains("\"name\": \"Before\""));
		assertFalse(encoded.contains("\"name\": \"After\""));
		assertTrue(persistence.needsSaving());

		PortalSaveSnapshot latest = persistence.prepareSave();
		assertNotNull(latest);
		persistence.writeSave(latest);
		assertFalse(persistence.needsSaving());
		assertTrue(Files.readString(stateFile).contains("\"name\": \"After\""));
	}

	@Test
	void rejectedAsyncSaveReleasesThePortalForRetryWithoutWriting() throws Exception
	{
		World world = LocalPortalTestSupport.world("rejected-save");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		Path stateFile = temporaryDirectory.resolve("portal.json");
		LocalPortalPersistence persistence = new LocalPortalPersistence(portal, ignored -> stateFile.toFile());
		persistence.save();

		PortalSaveSnapshot prepared = persistence.prepareSave();
		assertNotNull(prepared);
		assertFalse(persistence.needsSaving());
		persistence.rejectSave();

		assertTrue(persistence.needsSaving());
		assertFalse(Files.exists(stateFile));
		assertNotNull(persistence.prepareSave());
		persistence.rejectSave();
	}

	@Test
	void deletionWinsOverAnAlreadyPreparedAsyncSave() throws Exception
	{
		World world = LocalPortalTestSupport.world("deleted-save");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		Path stateFile = temporaryDirectory.resolve("nested").resolve("portal.json");
		LocalPortalPersistence persistence = new LocalPortalPersistence(portal, ignored -> stateFile.toFile());
		persistence.save();
		PortalSaveSnapshot prepared = persistence.prepareSave();
		assertNotNull(prepared);

		persistence.deleteData();
		persistence.writeSave(prepared);

		assertFalse(Files.exists(stateFile));
		assertFalse(persistence.needsSaving());
	}

	@Test
	void synchronousShutdownSaveSupersedesAQueuedStaleSnapshot() throws Exception
	{
		World world = LocalPortalTestSupport.world("shutdown-save");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		Path stateFile = temporaryDirectory.resolve("portal.json");
		LocalPortalPersistence persistence = new LocalPortalPersistence(portal, ignored -> stateFile.toFile());
		portal.setName("Queued");
		persistence.save();
		PortalSaveSnapshot queued = persistence.prepareSave();
		assertNotNull(queued);

		portal.setName("Shutdown");
		persistence.save();
		persistence.saveNow();
		persistence.writeSave(queued);

		String encoded = Files.readString(stateFile);
		assertTrue(encoded.contains("\"name\": \"Shutdown\""));
		assertFalse(encoded.contains("\"name\": \"Queued\""));
		assertFalse(persistence.needsSaving());
	}

	@Test
	void snapshotCaptureErrorReleasesThePortalForRetry()
	{
		World world = LocalPortalTestSupport.world("snapshot-error");
		LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
		Path stateFile = temporaryDirectory.resolve("portal.json");
		AtomicBoolean fail = new AtomicBoolean(true);
		LocalPortalPersistence persistence = new LocalPortalPersistence(portal, ignored ->
		{
			if(fail.getAndSet(false))
			{
				throw new AssertionError("capture failed");
			}
			return stateFile.toFile();
		});
		persistence.save();

		assertThrows(AssertionError.class, persistence::prepareSave);
		assertNotNull(persistence.prepareSave());
		persistence.rejectSave();
	}
}
