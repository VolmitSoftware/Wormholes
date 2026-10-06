package art.arcane.optics.stream;


import java.util.Objects;
import java.util.regex.Pattern;
import art.arcane.optics.frame.OpticTransform;

public record ProjectionEnvironment(long gameTime, Sky sky, Fog fog, Lighting lighting, Clouds clouds,
                                    OpticTransform transform, Dimension dimension, World world) {
    private static final Pattern DIMENSION_KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public ProjectionEnvironment {
        Objects.requireNonNull(sky, "sky");
        Objects.requireNonNull(fog, "fog");
        Objects.requireNonNull(lighting, "lighting");
        Objects.requireNonNull(clouds, "clouds");
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(world, "world");
    }

    public ProjectionEnvironment withTransform(OpticTransform value) {
        return new ProjectionEnvironment(gameTime, sky, fog, lighting, clouds, value, dimension, world);
    }

    private static void finite(float... values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Environment values must be finite");
            }
        }
    }

    public record Color(float red, float green, float blue) {
        public Color {
            finite(red, green, blue);
        }
    }

    public record ColorAlpha(float red, float green, float blue, float alpha) {
        public ColorAlpha {
            finite(red, green, blue, alpha);
        }
    }

    public record Sky(Skybox skybox, float sunAngle, float moonAngle, float starAngle, float starBrightness,
                      ColorAlpha sunrise, Color color, int moonPhase, float rain, float thunder) {
        public Sky {
            Objects.requireNonNull(skybox, "skybox");
            Objects.requireNonNull(sunrise, "sunrise");
            Objects.requireNonNull(color, "color");
            finite(sunAngle, moonAngle, starAngle, starBrightness, rain, thunder);
            if (moonPhase < 0 || moonPhase > 7) {
                throw new IllegalArgumentException("Invalid moon phase");
            }
        }
    }

    public record Fog(Color color, float start, float end, float skyEnd, float cloudEnd,
                      Color waterColor, float waterStart, float waterEnd) {
        public Fog {
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(waterColor, "waterColor");
            finite(start, end, skyEnd, cloudEnd, waterStart, waterEnd);
        }
    }

    public record Lighting(Color blockTint, float skyFactor, Color skyColor, Color ambient) {
        public Lighting {
            Objects.requireNonNull(blockTint, "blockTint");
            Objects.requireNonNull(skyColor, "skyColor");
            Objects.requireNonNull(ambient, "ambient");
            finite(skyFactor);
        }
    }

    public record Clouds(ColorAlpha color, float height) {
        public Clouds {
            Objects.requireNonNull(color, "color");
            finite(height);
        }
    }

    public record Dimension(int minY, int height, boolean hasSkyLight, CardinalLighting cardinalLighting,
                            double horizonHeight, boolean endFlashes) {
        public Dimension {
            Objects.requireNonNull(cardinalLighting, "cardinalLighting");
            if (height <= 0 || !Double.isFinite(horizonHeight)) {
                throw new IllegalArgumentException("Invalid environment dimension");
            }
        }
    }

    public record World(String dimensionKey, long clockTime, String biomeKey, int seaLevel, int blockLight, int skyLight, int logicalHeight, boolean hasCeiling, float ambientLight,
                        EyeMedium eyeMedium, boolean hasFixedTime) {
        public World {
            Objects.requireNonNull(dimensionKey, "dimensionKey");
            Objects.requireNonNull(biomeKey, "biomeKey");
            Objects.requireNonNull(eyeMedium, "eyeMedium");
            if (dimensionKey.length() > ViewStreamLimits.MAX_STRING_BYTES || !DIMENSION_KEY.matcher(dimensionKey).matches()) {
                throw new IllegalArgumentException("Invalid destination dimension identifier");
            }
            if (biomeKey.length() > ViewStreamLimits.MAX_STRING_BYTES || !DIMENSION_KEY.matcher(biomeKey).matches()) {
                throw new IllegalArgumentException("Invalid destination biome identifier");
            }
            if (blockLight < 0 || blockLight > 15 || skyLight < 0 || skyLight > 15) {
                throw new IllegalArgumentException("Invalid destination eye brightness");
            }
            finite(ambientLight);
            if (logicalHeight < 0) {
                throw new IllegalArgumentException("Invalid destination logical height");
            }
        }
    }

    public enum EyeMedium {
        NONE, WATER, LAVA, POWDER_SNOW
    }

    public enum Skybox {
        NONE, OVERWORLD, END
    }

    public enum CardinalLighting {
        DEFAULT, NETHER
    }
}
