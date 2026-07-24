package art.arcane.wormholes.portal;

import java.util.Locale;

public enum ProjectionRenderMode
{
	PANOPTIC("PanOptic", "SPYGLASS"),
	VENTICULAR("Venticular", "TINTED_GLASS");

	private static final ProjectionRenderMode[] VALUES = values();

	private final String displayName;
	private final String iconMaterialName;

	ProjectionRenderMode(String displayName, String iconMaterialName)
	{
		this.displayName = displayName;
		this.iconMaterialName = iconMaterialName;
	}

	public String displayName()
	{
		return displayName;
	}

	public String iconMaterialName()
	{
		return iconMaterialName;
	}

	public ProjectionRenderMode next()
	{
		return VALUES[(ordinal() + 1) % VALUES.length];
	}

	public static ProjectionRenderMode fromName(String name, ProjectionRenderMode fallback)
	{
		if(name == null)
		{
			return fallback;
		}

		String key = name.trim().toUpperCase(Locale.ROOT);

		for(ProjectionRenderMode mode : VALUES)
		{
			if(mode.name().equals(key))
			{
				return mode;
			}
		}

		return fallback;
	}
}
