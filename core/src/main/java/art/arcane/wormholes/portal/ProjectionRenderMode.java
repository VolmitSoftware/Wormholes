package art.arcane.wormholes.portal;

import art.arcane.optics.scan.ScanMode;

import java.util.Locale;

public enum ProjectionRenderMode
{
	PANOPTIC("PanOptic", "SPYGLASS", new ScanMode(false, false)),
	VENTICULAR("Venticular", "TINTED_GLASS", new ScanMode(true, true));

	private static final ProjectionRenderMode[] VALUES = values();

	private final String displayName;
	private final String iconMaterialName;
	private final ScanMode scanMode;

	ProjectionRenderMode(String displayName, String iconMaterialName, ScanMode scanMode)
	{
		this.displayName = displayName;
		this.iconMaterialName = iconMaterialName;
		this.scanMode = scanMode;
	}

	public String displayName()
	{
		return displayName;
	}

	public String iconMaterialName()
	{
		return iconMaterialName;
	}

	public ScanMode scanMode()
	{
		return scanMode;
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
