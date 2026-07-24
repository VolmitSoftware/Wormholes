package art.arcane.wormholes.portal;

import java.util.Locale;
import java.util.Optional;

public final class PortalSurfaceSkins
{
	private PortalSurfaceSkins()
	{
	}

	public static String normalizeSkin(String skin)
	{
		if(skin == null)
		{
			return "";
		}

		String trimmed = skin.trim();
		if(trimmed.isEmpty())
		{
			return "";
		}

		return trimmed.toLowerCase(Locale.ROOT);
	}

	public static boolean isFluid(String skin)
	{
		String id = baseId(normalizeSkin(skin));
		return id.equals("water") || id.equals("lava");
	}

	public static boolean isTransparentSkin(String skin)
	{
		String normalized = normalizeSkin(skin);
		if(normalized.isEmpty())
		{
			return false;
		}

		if(isFluid(normalized))
		{
			return true;
		}

		String id = baseId(normalized);
		return id.contains("glass")
			|| id.endsWith("ice")
			|| id.contains("slime_block")
			|| id.contains("honey_block")
			|| id.contains("barrier");
	}

	public static Optional<String> skinForHeldItem(String materialName)
	{
		if(materialName == null)
		{
			return Optional.empty();
		}

		String key = materialName.trim().toUpperCase(Locale.ROOT);
		return switch(key)
		{
			case "WATER_BUCKET" -> Optional.of("minecraft:water");
			case "LAVA_BUCKET" -> Optional.of("minecraft:lava");
			default -> Optional.empty();
		};
	}

	private static String baseId(String normalized)
	{
		int bracket = normalized.indexOf('[');
		String withoutProperties = bracket >= 0 ? normalized.substring(0, bracket) : normalized;
		int colon = withoutProperties.indexOf(':');
		return colon >= 0 ? withoutProperties.substring(colon + 1) : withoutProperties;
	}
}
