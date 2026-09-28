package art.arcane.wormholes.portal.rtp;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class RtpSettingsCodec {
    private RtpSettingsCodec() {
    }

	public static RtpSettings readSettings(Map<String, Object> json, Function<String, RtpWorld> resolver)
	{
		Map<String, Object> requiredJson = Objects.requireNonNull(json, "json");
		Function<String, RtpWorld> requiredResolver = Objects.requireNonNull(resolver, "resolver");
		RtpWorld sourceWorld = Objects.requireNonNull(requiredResolver.apply(null), "source world");
		RtpSettings.Builder builder = RtpSettings.builder(sourceWorld);
		String targetWorldKey = string(requiredJson, "targetWorldKey", "").trim();
		if(!targetWorldKey.isEmpty())
		{
			try
			{
				String normalizedTargetWorldKey = normalizeWorldKey(targetWorldKey);
				if(sourceWorld.key().equals(normalizedTargetWorldKey))
				{
					builder.targetWorld(sourceWorld);
				}
				else
				{
					builder.targetWorld(normalizedTargetWorldKey, requiredResolver.apply(normalizedTargetWorldKey));
				}
			}
			catch(IllegalArgumentException exception)
			{
				builder.targetWorld(sourceWorld);
			}
		}

		RtpCenterMode centerMode = parseEnum(RtpCenterMode.class, string(requiredJson, "centerMode", ""), RtpCenterMode.PORTAL_RELATIVE);
		Double customCenterX = readBoundedCoordinate(requiredJson, "customCenterX");
		Double customCenterZ = readBoundedCoordinate(requiredJson, "customCenterZ");
		if((customCenterX == null) != (customCenterZ == null) || centerMode == RtpCenterMode.CUSTOM && customCenterX == null)
		{
			centerMode = RtpCenterMode.PORTAL_RELATIVE;
			customCenterX = null;
			customCenterZ = null;
		}
		builder.centerMode(centerMode);
		if(customCenterX != null)
		{
			builder.customCenter(customCenterX.doubleValue(), customCenterZ.doubleValue());
		}

		Integer storedMinimumRadius = readStoredInteger(requiredJson, "minimumRadius", RtpSettings.DEFAULT_MINIMUM_RADIUS);
		Integer storedMaximumRadius = readStoredInteger(requiredJson, "maximumRadius", RtpSettings.DEFAULT_MAXIMUM_RADIUS);
		int minimumRadius;
		int maximumRadius;
		if(storedMinimumRadius == null || storedMaximumRadius == null
				|| storedMinimumRadius.intValue() < 0
				|| storedMaximumRadius.intValue() <= storedMinimumRadius.intValue()
				|| storedMaximumRadius.intValue() > RtpSettings.MAXIMUM_RADIUS)
		{
			minimumRadius = RtpSettings.DEFAULT_MINIMUM_RADIUS;
			maximumRadius = RtpSettings.DEFAULT_MAXIMUM_RADIUS;
		}
		else
		{
			minimumRadius = storedMinimumRadius.intValue();
			maximumRadius = storedMaximumRadius.intValue();
		}
		builder.radii(minimumRadius, maximumRadius);
		builder.verticalMode(parseEnum(RtpVerticalMode.class, string(requiredJson, "verticalMode", ""), RtpVerticalMode.SURFACE));
		builder.safetyMode(parseEnum(RtpSafetyMode.class, string(requiredJson, "safetyMode", ""), RtpSafetyMode.SAFE));

		RtpWorld boundsWorld = builder.targetWorld == null ? sourceWorld : builder.targetWorld;
		int defaultLowerY = boundsWorld.minimumHeight() + 1;
		int defaultUpperY = boundsWorld.maximumHeight() - 2;
		int defaultPreferredY = RtpSettings.clamp(boundsWorld.seaLevel() + 1, defaultLowerY, defaultUpperY);
		int lowerY = integer(requiredJson, "lowerY", defaultLowerY);
		int upperY = integer(requiredJson, "upperY", defaultUpperY);
		int clampedLowerY = RtpSettings.clamp(lowerY, defaultLowerY, defaultUpperY);
		int clampedUpperY = RtpSettings.clamp(upperY, defaultLowerY, defaultUpperY);
		if(clampedLowerY > clampedUpperY)
		{
			clampedLowerY = defaultLowerY;
			clampedUpperY = defaultUpperY;
		}
		builder.yBounds(clampedLowerY, clampedUpperY);
		builder.preferredY(integer(requiredJson, "preferredY", defaultPreferredY));
		builder.allocationMode(parseEnum(RtpAllocationMode.class, string(requiredJson, "allocationMode", ""), RtpAllocationMode.SHARED));
		builder.rotationMode(parseEnum(RtpRotationMode.class, string(requiredJson, "rotationMode", ""), RtpRotationMode.ON_TRAVERSAL));
		builder.cycleDurationMillis(longValue(requiredJson, "cycleDurationMillis", RtpSettings.DEFAULT_CYCLE_DURATION_MILLIS));
		builder.leaseIdleMillis(longValue(requiredJson, "leaseIdleMillis", RtpSettings.DEFAULT_LEASE_IDLE_MILLIS));
		builder.privateReleaseMillis(longValue(requiredJson, "privateReleaseMillis", RtpSettings.DEFAULT_PRIVATE_RELEASE_MILLIS));
		builder.rimEnabled(flag(requiredJson, "rimEnabled", true));
		builder.soundEnabled(flag(requiredJson, "soundEnabled", true));
		builder.targetBiomeKey(string(requiredJson, "targetBiomeKey", null));
		return builder.build();
	}

	public static Map<String, Object> writeSettings(RtpSettings settings)
	{
		Map<String, Object> json = new LinkedHashMap<>();
		if(!settings.isSourceWorldTarget())
		{
			json.put("targetWorldKey", settings.getTargetWorldKey());
		}
		json.put("centerMode", settings.getCenterMode().name());
		if(settings.getCustomCenterX() != null)
		{
			json.put("customCenterX", settings.getCustomCenterX().doubleValue());
			json.put("customCenterZ", settings.getCustomCenterZ().doubleValue());
		}
		json.put("minimumRadius", settings.getMinimumRadius());
		json.put("maximumRadius", settings.getMaximumRadius());
		json.put("verticalMode", settings.getVerticalMode().name());
		json.put("safetyMode", settings.getSafetyMode().name());
		json.put("lowerY", settings.getLowerY());
		json.put("upperY", settings.getUpperY());
		json.put("preferredY", settings.getPreferredY());
		json.put("allocationMode", settings.getAllocationMode().name());
		json.put("rotationMode", settings.getRotationMode().name());
		json.put("cycleDurationMillis", settings.getCycleDurationMillis());
		json.put("leaseIdleMillis", settings.getLeaseIdleMillis());
		json.put("privateReleaseMillis", settings.getPrivateReleaseMillis());
		json.put("rimEnabled", settings.isRimEnabled());
		json.put("soundEnabled", settings.isSoundEnabled());
		if(settings.getTargetBiomeKey() != null)
		{
			json.put("targetBiomeKey", settings.getTargetBiomeKey());
		}
		return json;
	}

	private static Double readBoundedCoordinate(Map<String, Object> json, String key)
	{
		if(!json.containsKey(key))
		{
			return null;
		}
		double value = decimal(json, key, Double.NaN);
		return RtpSettings.isBoundedCoordinate(value) ? Double.valueOf(value) : null;
	}

	private static Integer readStoredInteger(Map<String, Object> json, String key, int defaultValue)
	{
		if(!json.containsKey(key))
		{
			return Integer.valueOf(defaultValue);
		}
		Object value = json.get(key);
		if(value == null)
		{
			return null;
		}
		try
		{
			return Integer.valueOf(new BigDecimal(value.toString()).intValueExact());
		}
		catch(NumberFormatException | ArithmeticException exception)
		{
			return null;
		}
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback)
	{
		if(value == null || value.isBlank())
		{
			return fallback;
		}
		try
		{
			return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
		}
		catch(IllegalArgumentException exception)
		{
			return fallback;
		}
	}

    private static String normalizeWorldKey(String key) {
        if (!key.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new IllegalArgumentException("Invalid world key");
        }
        return key;
    }

    private static String string(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int integer(Map<String, Object> values, String key, int fallback) {
        return (int) longValue(values, key, fallback);
    }

    private static long longValue(Map<String, Object> values, String key, long fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : new BigDecimal(value.toString()).longValue();
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }

    private static double decimal(Map<String, Object> values, String key, double fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString());
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }

    private static boolean flag(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key);
        if (value instanceof Boolean enabled) {
            return enabled;
        }
        if (value instanceof String text) {
            if (text.equalsIgnoreCase("true")) {
                return true;
            }
            if (text.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return fallback;
    }
}
