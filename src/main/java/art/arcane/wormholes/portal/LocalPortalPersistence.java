package art.arcane.wormholes.portal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Level;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.util.JSONObject;
import art.arcane.wormholes.util.VIO;

final class LocalPortalPersistence
{
	private final LocalPortal portal;
	private final Function<UUID, File> saveFileResolver;
	private final AtomicLong dirtyGeneration = new AtomicLong();
	private final AtomicBoolean saveInFlight = new AtomicBoolean();
	private final AtomicBoolean deleted = new AtomicBoolean();
	private final Object persistenceLock = new Object();
	private volatile long savedGeneration;

	LocalPortalPersistence(LocalPortal portal)
	{
		this(portal, id -> Wormholes.portalManager.getSaveFile(id));
	}

	LocalPortalPersistence(LocalPortal portal, Function<UUID, File> saveFileResolver)
	{
		this.portal = Objects.requireNonNull(portal, "portal");
		this.saveFileResolver = Objects.requireNonNull(saveFileResolver, "saveFileResolver");
		savedGeneration = dirtyGeneration.get();
	}

	void markClean()
	{
		savedGeneration = dirtyGeneration.get();
	}

	void writeState(JSONObject j)
	{
		j.put("structure", portal.getStructure().toJSON());
		j.put("type", portal.getType().name());
		j.put("owner", portal.getOwner().toString());
		j.put("projectionMode", portal.settings().getProjectionMode().name());
		j.put("mirrorMode", portal.settings().isMirrorMode());
		j.put("mirrorRotationDegrees", portal.settings().getMirrorRotation().getDegrees());
		portal.rtp().save(j);
		portal.settings().save(j);
		portal.linking().save(j);
	}

	void readState(JSONObject j)
	{
		PortalStructure structure = new PortalStructure();
		portal.assignStructure(structure);
		structure.loadJSON(j.getJSONObject("structure"));
		if(!portal.hasFrameLoadedFromJson())
		{
			portal.applyFrame(PortalFrame.derive(structure.getArea(), portal.direction));
		}
		portal.assignType(PortalType.valueOf(j.getString("type")));
		portal.rtp().load(j);
		portal.setOwner(UUID.fromString(j.getString("owner")));
		boolean projectionStateNormalized = portal.settings().load(j);
		portal.gate().rebuildView();

		portal.linking().load(j);
		boolean dimensionalStateNormalized = portal.linking().normalizeDimensionalState();
		boolean rtpStateNormalized = portal.rtp().normalizeState();
		boolean rtpPersistenceNormalized = portal.rtp().requiresPersistenceNormalization(j);
		if(dimensionalStateNormalized || rtpStateNormalized || rtpPersistenceNormalized || projectionStateNormalized)
		{
			save();
		}
	}

	void save()
	{
		if(portal.isDestroyed())
		{
			return;
		}
		dirtyGeneration.incrementAndGet();
	}

	boolean needsSaving()
	{
		return !deleted.get() && !saveInFlight.get() && dirtyGeneration.get() != savedGeneration;
	}

	PortalSaveSnapshot prepareSave()
	{
		if(deleted.get() || portal.isDestroyed() || !saveInFlight.compareAndSet(false, true))
		{
			return null;
		}
		try
		{
			return captureSnapshot();
		}
		catch(RuntimeException | Error error)
		{
			saveInFlight.set(false);
			throw error;
		}
	}

	void writeSave(PortalSaveSnapshot snapshot) throws IOException
	{
		try
		{
			synchronized(persistenceLock)
			{
				if(deleted.get())
				{
					return;
				}
				if(snapshot.generation() < savedGeneration)
				{
					return;
				}
				File file = snapshot.file();
				file.getParentFile().mkdirs();
				VIO.writeAll(file, snapshot.encoded());
				savedGeneration = snapshot.generation();
				Wormholes.v("Saved Portal " + snapshot.portalId() + " (" + snapshot.portalName() + ")");
			}
		}
		finally
		{
			saveInFlight.set(false);
		}
	}

	void rejectSave()
	{
		saveInFlight.set(false);
	}

	void saveNow() throws IOException
	{
		if(deleted.get() || portal.isDestroyed())
		{
			return;
		}
		try
		{
			synchronized(persistenceLock)
			{
				if(deleted.get() || portal.isDestroyed())
				{
					return;
				}
				PortalSaveSnapshot snapshot = captureSnapshot();
				File file = snapshot.file();
				file.getParentFile().mkdirs();
				VIO.writeAll(file, snapshot.encoded());
				savedGeneration = snapshot.generation();
				Wormholes.v("Saved Portal " + snapshot.portalId() + " (" + snapshot.portalName() + ")");
			}
		}
		finally
		{
			saveInFlight.set(false);
		}
	}

	void deleteData()
	{
		deleted.set(true);
		synchronized(persistenceLock)
		{
			File f = saveFileResolver.apply(portal.getId());
			try
			{
				Files.deleteIfExists(f.toPath());
			}
			catch(IOException e)
			{
				Wormholes.instance.getLogger().log(Level.WARNING, "Could not delete portal data for " + portal.getId(), e);
				return;
			}
			deleteDirectoryIfEmpty(f.getParentFile());
			deleteDirectoryIfEmpty(f.getParentFile().getParentFile());
		}
	}

	private static void deleteDirectoryIfEmpty(File directory)
	{
		File[] contents = directory == null ? null : directory.listFiles();
		if(contents != null && contents.length == 0)
		{
			directory.delete();
		}
	}

	private PortalSaveSnapshot captureSnapshot()
	{
		long generation = dirtyGeneration.get();
		File file = saveFileResolver.apply(portal.getId());
		return new PortalSaveSnapshot(
			file,
			portal.toJSON().toString(2),
			generation,
			portal.getId(),
			portal.getName()
		);
	}

	static UUID resolveOptionalUuid(String value)
	{
		if(value == null || value.isBlank())
		{
			return null;
		}
		try
		{
			return UUID.fromString(value);
		}
		catch(IllegalArgumentException ignored)
		{
			return null;
		}
	}
}
