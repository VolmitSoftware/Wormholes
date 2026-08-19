package art.arcane.wormholes.portal.rtp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;

import art.arcane.wormholes.platform.WormholesPlatform;

public final class RtpBiomeDirectory
{
	private RtpBiomeDirectory()
	{
	}

	/** Selectable biomes for the world: Iris pack biomes when the world is an open Iris world, the vanilla registry otherwise. */
	public static List<RtpPortalEditorModel.BiomeOption> optionsFor(World world)
	{
		if(world == null)
		{
			return List.of();
		}
		Map<String, RtpPortalEditorModel.BiomeOption> options = new LinkedHashMap<String, RtpPortalEditorModel.BiomeOption>();
		List<IrisBiomeProbe.BiomeInfo> irisBiomes = IrisBiomeProbe.shared().allBiomes(world);
		if(irisBiomes != null && !irisBiomes.isEmpty())
		{
			for(IrisBiomeProbe.BiomeInfo biome : irisBiomes)
			{
				String key = RtpBiomeMatcher.normalize(biome.loadKey());
				if(key != null)
				{
					options.putIfAbsent(key, new RtpPortalEditorModel.BiomeOption(key, biome.displayName()));
				}
			}
		}
		else
		{
			try
			{
				for(Biome biome : Registry.BIOME)
				{
					String key = RtpBiomeMatcher.normalize(WormholesPlatform.keyString(biome.getKey()));
					if(key != null)
					{
						options.putIfAbsent(key, new RtpPortalEditorModel.BiomeOption(key, prettyPath(key)));
					}
				}
			}
			catch(RuntimeException | LinkageError failure)
			{
				return List.of();
			}
		}
		List<RtpPortalEditorModel.BiomeOption> sorted = new ArrayList<RtpPortalEditorModel.BiomeOption>(options.values());
		sorted.sort(Comparator
				.comparing(RtpPortalEditorModel.BiomeOption::displayName, String.CASE_INSENSITIVE_ORDER)
				.thenComparing(RtpPortalEditorModel.BiomeOption::key));
		return List.copyOf(sorted);
	}

	private static String prettyPath(String key)
	{
		int separator = key.lastIndexOf(':');
		String path = separator < 0 ? key : key.substring(separator + 1);
		String[] words = path.split("_");
		StringBuilder pretty = new StringBuilder(path.length());
		for(String word : words)
		{
			if(word.isEmpty())
			{
				continue;
			}
			if(!pretty.isEmpty())
			{
				pretty.append(' ');
			}
			pretty.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
		}
		return pretty.isEmpty() ? key : pretty.toString();
	}
}
