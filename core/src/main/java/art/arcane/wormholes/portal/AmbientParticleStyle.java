package art.arcane.wormholes.portal;

import java.util.Locale;

public enum AmbientParticleStyle
{
	SPARKS("FIREWORK_STAR"),
	OUTLINE("BLAZE_ROD"),
	CORNERS("END_ROD"),
	OFF("GLASS");

	private static final AmbientParticleStyle[] VALUES = values();

	private final String iconMaterialName;

	AmbientParticleStyle(String iconMaterialName)
	{
		this.iconMaterialName = iconMaterialName;
	}

	public String iconMaterialName()
	{
		return iconMaterialName;
	}

	public String displayName()
	{
		String[] parts = name().split("_");
		StringBuilder builder = new StringBuilder();

		for(int i = 0; i < parts.length; i++)
		{
			if(i > 0)
			{
				builder.append(' ');
			}

			String part = parts[i];
			builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
			builder.append(part.substring(1).toLowerCase(Locale.ROOT));
		}

		return builder.toString();
	}

	public AmbientParticleStyle next()
	{
		return VALUES[(ordinal() + 1) % VALUES.length];
	}

	public static AmbientParticleStyle fromName(String name, AmbientParticleStyle fallback)
	{
		if(name == null)
		{
			return fallback;
		}

		String key = name.trim().toUpperCase(Locale.ROOT);

		for(AmbientParticleStyle style : VALUES)
		{
			if(style.name().equals(key))
			{
				return style;
			}
		}

		return fallback;
	}
}
