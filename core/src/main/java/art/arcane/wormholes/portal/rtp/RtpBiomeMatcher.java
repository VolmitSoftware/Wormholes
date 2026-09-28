package art.arcane.wormholes.portal.rtp;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;

public final class RtpBiomeMatcher
{
	private RtpBiomeMatcher()
	{
	}

	public static String normalize(String key)
	{
		if(key == null)
		{
			return null;
		}
		String normalized = key.trim().toLowerCase(Locale.ROOT);
		return normalized.isEmpty() ? null : normalized;
	}

	public static boolean matches(String targetKey, Collection<String> candidateKeys)
	{
		String target = Objects.requireNonNull(normalize(targetKey), "targetKey");
		for(String candidateKey : Objects.requireNonNull(candidateKeys, "candidateKeys"))
		{
			String candidate = normalize(candidateKey);
			if(candidate == null)
			{
				continue;
			}
			if(target.equals(candidate) || path(target).equals(candidate) || target.equals(path(candidate)))
			{
				return true;
			}
		}
		return false;
	}

	private static String path(String key)
	{
		int separator = key.lastIndexOf(':');
		return separator < 0 ? key : key.substring(separator + 1);
	}
}
