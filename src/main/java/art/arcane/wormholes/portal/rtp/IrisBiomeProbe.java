package art.arcane.wormholes.portal.rtp;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

public final class IrisBiomeProbe
{
	private static final String TOOLBELT_CLASS = "art.arcane.iris.core.tools.IrisToolbelt";
	private static final IrisBiomeProbe SHARED = new IrisBiomeProbe(TOOLBELT_CLASS, IrisBiomeProbe::irisClassLoader);

	private final String toolbeltClassName;
	private final Supplier<ClassLoader> classLoaderSupplier;
	private volatile Resolution resolution;

	IrisBiomeProbe(String toolbeltClassName, Supplier<ClassLoader> classLoaderSupplier)
	{
		this.toolbeltClassName = Objects.requireNonNull(toolbeltClassName, "toolbeltClassName");
		this.classLoaderSupplier = Objects.requireNonNull(classLoaderSupplier, "classLoaderSupplier");
	}

	public static IrisBiomeProbe shared()
	{
		return SHARED;
	}

	/** Load key plus vanilla derivative key of the pack biome at the column; null when the world is not an open Iris world. */
	public List<String> biomeKeysAt(World world, int blockX, int blockZ, Integer probeY)
	{
		try
		{
			Resolution active = resolve();
			Object engine = openEngine(active, world);
			if(engine == null)
			{
				return null;
			}
			Object biome = probeY == null
					? active.getSurfaceBiome().invoke(engine, Integer.valueOf(blockX), Integer.valueOf(blockZ))
					: active.getBiome().invoke(engine, Integer.valueOf(blockX), probeY, Integer.valueOf(blockZ));
			if(biome == null)
			{
				return null;
			}
			List<String> keys = new ArrayList<String>(2);
			if(active.getLoadKey().invoke(biome) instanceof String loadKey)
			{
				keys.add(loadKey);
			}
			String derivativeKey = derivativeKey(active, biome);
			if(derivativeKey != null)
			{
				keys.add(derivativeKey);
			}
			return keys.isEmpty() ? null : keys;
		}
		catch(ReflectiveOperationException | RuntimeException | LinkageError failure)
		{
			return null;
		}
	}

	/** Reachable pack biomes of the world's dimension; null when the world is not an open Iris world. */
	public List<BiomeInfo> allBiomes(World world)
	{
		try
		{
			Resolution active = resolve();
			Object engine = openEngine(active, world);
			if(engine == null)
			{
				return null;
			}
			if(!(active.getAllBiomes().invoke(engine) instanceof List<?> biomes))
			{
				return null;
			}
			List<BiomeInfo> infos = new ArrayList<BiomeInfo>(biomes.size());
			for(Object biome : biomes)
			{
				if(biome == null || !(active.getLoadKey().invoke(biome) instanceof String loadKey))
				{
					continue;
				}
				String name = active.getName().invoke(biome) instanceof String biomeName ? biomeName : loadKey;
				infos.add(new BiomeInfo(loadKey, name, derivativeKey(active, biome)));
			}
			return infos;
		}
		catch(ReflectiveOperationException | RuntimeException | LinkageError failure)
		{
			return null;
		}
	}

	private String derivativeKey(Resolution active, Object biome) throws ReflectiveOperationException
	{
		Object derivative = active.getDerivative().invoke(biome);
		if(derivative == null)
		{
			return null;
		}
		Object key = derivative.getClass().getMethod("getKey").invoke(derivative);
		return key == null ? null : key.toString();
	}

	private Object openEngine(Resolution active, World world) throws ReflectiveOperationException
	{
		if(active == null)
		{
			return null;
		}
		Object generator = active.access().invoke(null, world);
		if(generator == null)
		{
			return null;
		}
		Object engine = active.getEngine().invoke(generator);
		if(engine == null || Boolean.TRUE.equals(active.isClosed().invoke(engine)))
		{
			return null;
		}
		return engine;
	}

	private Resolution resolve()
	{
		ClassLoader loader = classLoaderSupplier.get();
		if(loader == null)
		{
			return null;
		}
		Resolution cached = resolution;
		if(cached != null && cached.loader() == loader)
		{
			return cached;
		}
		try
		{
			Class<?> toolbelt = Class.forName(toolbeltClassName, false, loader);
			Method access = toolbelt.getMethod("access", World.class);
			Method getEngine = access.getReturnType().getMethod("getEngine");
			Class<?> engineType = getEngine.getReturnType();
			Method isClosed = engineType.getMethod("isClosed");
			Method getSurfaceBiome = engineType.getMethod("getSurfaceBiome", int.class, int.class);
			Method getBiome = engineType.getMethod("getBiome", int.class, int.class, int.class);
			Method getAllBiomes = engineType.getMethod("getAllBiomes");
			Class<?> biomeType = getSurfaceBiome.getReturnType();
			Method getLoadKey = biomeType.getMethod("getLoadKey");
			Method getName = biomeType.getMethod("getName");
			Method getDerivative = biomeType.getMethod("getDerivative");
			Resolution resolved = new Resolution(loader, access, getEngine, isClosed,
					getSurfaceBiome, getBiome, getAllBiomes, getLoadKey, getName, getDerivative);
			resolution = resolved;
			return resolved;
		}
		catch(ReflectiveOperationException | RuntimeException | LinkageError failure)
		{
			return null;
		}
	}

	private static ClassLoader irisClassLoader()
	{
		Plugin plugin = Bukkit.getPluginManager().getPlugin("Iris");
		if(plugin == null || !plugin.isEnabled())
		{
			return null;
		}
		return plugin.getClass().getClassLoader();
	}

	public record BiomeInfo(String loadKey, String displayName, String derivativeKey)
	{
		public BiomeInfo
		{
			Objects.requireNonNull(loadKey, "loadKey");
			Objects.requireNonNull(displayName, "displayName");
		}
	}

	private record Resolution(
			ClassLoader loader,
			Method access,
			Method getEngine,
			Method isClosed,
			Method getSurfaceBiome,
			Method getBiome,
			Method getAllBiomes,
			Method getLoadKey,
			Method getName,
			Method getDerivative)
	{
	}
}
