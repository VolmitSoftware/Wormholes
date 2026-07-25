package art.arcane.wormholes;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Stream;

import art.arcane.volmlib.util.bukkit.WorldIdentity;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.util.JSONObject;
import art.arcane.wormholes.util.VIO;

final class PortalRegistryStorage
{
	File saveFile(UUID id)
	{
		return new File(new File(new File(portalFolder(), id.toString().split("-")[1]), id.toString().split("-")[0]), id.toString() + ".json");
	}

	List<File> listPortalFiles()
	{
		File portalFolder = portalFolder();
		portalFolder.mkdirs();

		File[] firstLevel = portalFolder.listFiles();
		if(firstLevel == null)
		{
			return null;
		}

		List<File> files = new ArrayList<File>();
		for(File i : firstLevel)
		{
			if(!i.isDirectory())
			{
				continue;
			}
			File[] secondLevel = i.listFiles();
			if(secondLevel == null)
			{
				continue;
			}
			for(File j : secondLevel)
			{
				if(!j.isDirectory())
				{
					continue;
				}
				File[] portalFiles = j.listFiles();
				if(portalFiles == null)
				{
					continue;
				}
				for(File k : portalFiles)
				{
					if(k.isFile() && k.getName().endsWith(".json"))
					{
						files.add(k);
					}
				}
			}
		}
		return files;
	}

	StoredPortal readPortal(File file) throws IOException
	{
		JSONObject j = new JSONObject(VIO.readAll(file));
		String savedWorldKey = j.getJSONObject("structure").getString("worldKey");

		if(WorldIdentity.resolve(savedWorldKey).isEmpty())
		{
			return new StoredPortal(savedWorldKey, null, true);
		}

		PortalType type = PortalType.valueOf(j.getString("type"));
		PortalStructure structure = new PortalStructure();
		structure.loadJSON(j.getJSONObject("structure"));

		if(structure.getWorld() == null)
		{
			return new StoredPortal(savedWorldKey, null, false);
		}

		ILocalPortal portal = new LocalPortal(UUID.fromString(j.getString("id")), type, structure);

		portal.loadJSON(j);
		return new StoredPortal(savedWorldKey, portal, false);
	}

	boolean isPairedCounterpart(File file, UUID portalId, UUID expectedCounterpartId)
	{
		try
		{
			JSONObject json = new JSONObject(VIO.readAll(file));
			boolean reciprocal = expectedCounterpartId.toString().equals(json.optString("dimensionalCounterpartId", ""));
			if(!reciprocal && json.has("tunnel"))
			{
				JSONObject tunnel = json.getJSONObject("tunnel");
				reciprocal = "DIMENSIONAL".equals(tunnel.optString("type", ""))
						&& expectedCounterpartId.toString().equals(tunnel.optString("destination", ""));
			}
			return reciprocal;
		}
		catch(IOException | RuntimeException e)
		{
			logCounterpartFailure("delete", portalId, expectedCounterpartId, e);
			return false;
		}
	}

	boolean deletePortalFile(File file, UUID portalId, UUID expectedCounterpartId)
	{
		try
		{
			Files.deleteIfExists(file.toPath());
			deleteDirectoryIfEmpty(file.getParentFile());
			deleteDirectoryIfEmpty(file.getParentFile().getParentFile());
			return true;
		}
		catch(IOException | RuntimeException e)
		{
			logCounterpartFailure("delete", portalId, expectedCounterpartId, e);
			return false;
		}
	}

	boolean clearPairedReference(File file, UUID portalId, UUID expectedCounterpartId)
	{
		try
		{
			JSONObject json = new JSONObject(VIO.readAll(file));
			boolean changed = false;
			if(expectedCounterpartId.toString().equals(json.optString("dimensionalCounterpartId", "")))
			{
				json.remove("dimensionalCounterpartId");
				changed = true;
			}
			if(json.has("tunnel"))
			{
				JSONObject tunnel = json.getJSONObject("tunnel");
				if("DIMENSIONAL".equals(tunnel.optString("type", ""))
						&& expectedCounterpartId.toString().equals(tunnel.optString("destination", "")))
				{
					json.remove("tunnel");
					changed = true;
				}
			}
			if(!changed)
			{
				return false;
			}
			VIO.writeAll(file, json.toString(2));
			return true;
		}
		catch(IOException | RuntimeException e)
		{
			logCounterpartFailure("clear", portalId, expectedCounterpartId, e);
			return false;
		}
	}

	void deletePortalFolder()
	{
		Path path = portalFolder().toPath();
		if(!Files.exists(path))
		{
			return;
		}
		try
		{
			Files.walkFileTree(path, new SimpleFileVisitor<Path>()
			{
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
				{
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
				{
					if(exc != null)
					{
						throw exc;
					}
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch(IOException e)
		{
			throw new IllegalStateException("Could not delete Wormholes portal data", e);
		}
	}

	private File portalFolder()
	{
		return new File(Wormholes.instance.getDataFolder(), "portals");
	}

	private static void logCounterpartFailure(String action, UUID portalId, UUID expectedCounterpartId, Throwable error)
	{
		Wormholes.instance.getLogger().log(Level.WARNING,
				"Could not " + action + " unloaded dimensional counterpart " + portalId + " paired with " + expectedCounterpartId, error);
	}

	private static void deleteDirectoryIfEmpty(File directory) throws IOException
	{
		if(directory == null || !directory.isDirectory())
		{
			return;
		}
		try(Stream<Path> entries = Files.list(directory.toPath()))
		{
			if(entries.findAny().isEmpty())
			{
				Files.deleteIfExists(directory.toPath());
			}
		}
	}

	record StoredPortal(String worldKey, ILocalPortal portal, boolean pendingWorld)
	{
	}
}
