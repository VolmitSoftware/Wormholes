package art.arcane.wormholes.modded.client.render.metal;

import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The members of Metal Shaders ({@code mcopt.metal}) that Wormholes drives, bound by name so the adapter has no
 * compile-time dependency on a jar that is not published to a Maven repository: the pass redirector chain, the
 * per-pipeline MSL registry, the texture handle bridge, the shading, weather and water preludes, the live per-frame
 * state, the water scene capture, the display-to-scene colour conversion and the flags that say which of its modules run.
 */
final class MetalShadersApi {
    private static final String HOOKS = "mcopt.metal.MetalHooks";
    private static final String REGISTRY = "mcopt.metal.MetalShaderRegistry";
    private static final String BRIDGE = "mcopt.metal.MetalBridge";
    private static final String MSL = "mcopt.metal.shade.ShadeMsl";
    private static final String FRAME_UPDATER = "mcopt.metal.shade.ShadeFrameUpdater";
    private static final String LOOK = "mcopt.metal.shade.ShadeLook";
    private static final String BINDINGS = "mcopt.metal.shade.ShadeBindings";
    private static final String CONFIG = "mcopt.metal.shade.ShadeConfig";
    private static final String POST = "mcopt.metal.shade.ShadePost";
    private static final String SHADE = "mcopt.metal.shade.Shade";
    private static final String FRAME = "mcopt.metal.shade.ShadeFrame";
    private static final String WATER = "mcopt.metal.shade.water.ShadeWater";
    private static final String WEATHER = "mcopt.metal.shade.weather.ShadeWeather";
    private static final String ENCODER_BACKEND = "com.mojang.renderpearl.backend.api.CommandEncoderBackend";
    private static final String CLOUDS = "mcopt.metal.shade.clouds.ShadeClouds";
    private static final String CLOUD_PARAMS = "mcopt.metal.shade.clouds.CloudParams";
    private static final String DIMS = "mcopt.metal.shade.dims.ShadeDims";
    private static final String DIMS_CONFIG = "mcopt.metal.shade.dims.DimsConfig";

    /** Metal Shaders' pixel format code for RGBA16Float, the format its HDR world target and its format adapter key on. */
    static final int RGBA16_FLOAT = 115;

    private final Class<?> redirectorType;
    private final MethodHandle addRedirector;
    private final MethodHandle redirectFormats;
    private final MethodHandle register;
    private final MethodHandle newVariant;
    private final MethodHandle textureHandle;
    private final MethodHandle prelude;
    private final MethodHandle look;
    private final MethodHandle ldrToHdr;
    private final MethodHandle postActive;
    private final MethodHandle frame;
    private final MethodHandle packFrame;
    private final MethodHandle waterPrelude;
    private final MethodHandle weatherPrelude;
    private final MethodHandle encoder;
    private final MethodHandle captureWater;
    private final MethodHandle resource;
    private final ByteBuffer cloudsPacked;
    private final Object cloudParams;
    private final Field[] cloudWind;
    private final Field cloudVanillaCells;
    private final Field exposureReference;
    private final MethodHandle dimsOn;
    private final MethodHandle userGain;
    private final boolean shadingOn;
    private final boolean hdr;
    private final boolean water;

    private MetalShadersApi(ClassLoader loader) throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> hooks = Class.forName(HOOKS, true, loader);
        redirectorType = Class.forName(HOOKS + "$PassRedirector", true, loader);
        Class<?> redirect = Class.forName(HOOKS + "$Redirect", true, loader);
        Class<?> registry = Class.forName(REGISTRY, true, loader);
        Class<?> variant = Class.forName(REGISTRY + "$Variant", true, loader);
        Class<?> bridge = Class.forName(BRIDGE, true, loader);
        Class<?> msl = Class.forName(MSL, true, loader);
        Class<?> frameUpdater = Class.forName(FRAME_UPDATER, true, loader);
        Class<?> lookType = Class.forName(LOOK, true, loader);
        Class<?> bindings = Class.forName(BINDINGS, true, loader);
        Class<?> config = Class.forName(CONFIG, true, loader);
        Class<?> post = Class.forName(POST, true, loader);
        addRedirector = lookup.findStatic(hooks, "addRedirector", MethodType.methodType(void.class, int.class, redirectorType));
        redirectFormats = lookup.findStatic(redirect, "formats", MethodType.methodType(redirect, long[].class, float[][].class, int.class, int.class, int[].class));
        register = lookup.findStatic(registry, "register", MethodType.methodType(void.class, String.class, variant));
        newVariant = lookup.findConstructor(variant, MethodType.methodType(void.class, UnaryOperator.class, UnaryOperator.class, List.class));
        textureHandle = lookup.findStatic(bridge, "textureHandle", MethodType.methodType(long.class, GpuTexture.class));
        prelude = lookup.findStatic(msl, "prelude", MethodType.methodType(String.class));
        look = lookup.findStatic(frameUpdater, "look", MethodType.methodType(lookType));
        ldrToHdr = lookup.findVirtual(lookType, "ldrToHdr", MethodType.methodType(float[].class, float.class, float.class, float.class));
        postActive = lookup.findStatic(post, "active", MethodType.methodType(boolean.class));
        Class<?> shade = Class.forName(SHADE, true, loader);
        Class<?> frameType = Class.forName(FRAME, true, loader);
        Class<?> waterType = Class.forName(WATER, true, loader);
        Class<?> weatherType = Class.forName(WEATHER, true, loader);
        Class<?> encoderBackend = Class.forName(ENCODER_BACKEND, true, loader);
        frame = lookup.findStatic(shade, "frame", MethodType.methodType(frameType));
        packFrame = lookup.findVirtual(frameType, "pack", MethodType.methodType(void.class, ByteBuffer.class));
        waterPrelude = lookup.findStatic(waterType, "prelude", MethodType.methodType(String.class));
        weatherPrelude = lookup.findStatic(weatherType, "prelude", MethodType.methodType(String.class, boolean.class));
        encoder = lookup.findStatic(bridge, "encoder", MethodType.methodType(Object.class, encoderBackend));
        captureWater = lookup.findStatic(bridge, "captureWater", MethodType.methodType(void.class, Object.class));
        resource = lookup.findStatic(msl, "resource", MethodType.methodType(String.class, String.class));
        Class<?> clouds = Class.forName(CLOUDS, true, loader);
        Class<?> cloudParamsType = Class.forName(CLOUD_PARAMS, true, loader);
        Field packed = clouds.getDeclaredField("PACKED");
        packed.setAccessible(true);
        cloudsPacked = (ByteBuffer) packed.get(null);
        Field params = clouds.getDeclaredField("PARAMS");
        params.setAccessible(true);
        cloudParams = params.get(null);
        cloudWind = new Field[] {cloudParamsType.getField("windX"), cloudParamsType.getField("windZ"), cloudParamsType.getField("wind3X"),
            cloudParamsType.getField("wind3Z"), cloudParamsType.getField("cirrusX"), cloudParamsType.getField("cirrusZ")};
        cloudVanillaCells = cloudParamsType.getField("vanillaCells");
        exposureReference = lookType.getField("exposureRef");
        MethodHandle dims = null;
        MethodHandle gain = null;
        try {
            Class<?> dimsType = Class.forName(DIMS, true, loader);
            Class<?> dimsConfig = Class.forName(DIMS_CONFIG, true, loader);
            dims = lookup.findStatic(dimsType, "on", MethodType.methodType(boolean.class));
            Method user = dimsConfig.getDeclaredMethod("user", String.class);
            user.setAccessible(true);
            gain = lookup.unreflect(user);
        } catch (ClassNotFoundException absent) {
            dims = null;
            gain = null;
        }
        dimsOn = dims;
        userGain = gain;
        shadingOn = bindings.getField("ON").getBoolean(null);
        hdr = config.getField("HDR").getBoolean(null);
        water = waterType.getField("ON").getBoolean(null);
    }

    /** Whether Metal Shaders' water module is on this session. */
    boolean waterOn() {
        return water;
    }

    /** Whether Metal Shaders' dims module, which lights the Nether and the End per pixel and draws the End's sky, is on this session. */
    boolean dimsOn() {
        if (dimsOn == null) {
            return false;
        }
        try {
            return (boolean) dimsOn.invoke();
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders hid its dims module", failure);
        }
    }

    /** The look's reference exposure this frame: the frame's eye-adapted exposure under the player's EV settings. */
    float exposureReference() {
        try {
            return exposureReference.getFloat(look.invoke());
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders hid its reference exposure", failure);
        }
    }

    /** One of the player's Metal Shaders light gains by its setting key, 1 as released; 1 without the dims module. */
    float userGain(String key) {
        if (userGain == null) {
            return 1.0f;
        }
        try {
            return (float) userGain.invoke(key);
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders hid its light settings", failure);
        }
    }

    /** Metal Shaders' live per-frame state packed into 1024 bytes at the buffer's position, in its own layout. */
    void packFrame(ByteBuffer out) {
        try {
            packFrame.invoke(frame.invoke(), out);
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders could not pack its frame", failure);
        }
    }

    /** Metal Shaders' water module source, the defines of its settings first. */
    String waterPrelude() {
        try {
            return (String) waterPrelude.invoke();
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders gave no water prelude", failure);
        }
    }

    /** Metal Shaders' weather module source for a translucent stage, which the water module's rain ripples need. */
    String weatherPrelude() {
        try {
            return (String) weatherPrelude.invoke(true);
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders gave no weather prelude", failure);
        }
    }

    /** One of Metal Shaders' shader sources by file name, for example {@code clouds.metal}. */
    String source(String name) {
        try {
            return (String) resource.invoke(name);
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders gave no shader source " + name, failure);
        }
    }

    /** The 448 bytes of cloud parameters Metal Shaders last marched its clouds with; stale on frames without clouds. */
    ByteBuffer cloudsPacked() {
        return cloudsPacked;
    }

    /** The cloud layer's accumulated wind offsets: weather x, z; noise x, z; cirrus x, z (blocks). */
    double[] cloudWind() {
        try {
            double[] wind = new double[cloudWind.length];
            for (int index = 0; index < wind.length; index++) {
                wind[index] = cloudWind[index].getDouble(cloudParams);
            }
            return wind;
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Metal Shaders hid its cloud wind", failure);
        }
    }

    /** Whether the clouds are the blocky style built on the game's cloud texture, whose weather map Wormholes does not hold. */
    boolean cloudVanillaCells() {
        try {
            return cloudVanillaCells.getBoolean(cloudParams);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Metal Shaders hid its cloud style", failure);
        }
    }

    /** Copies the open render pass's colour and depth into the water module's scene and depth copies. */
    void captureWater(CommandEncoderBackend backend) {
        try {
            captureWater.invoke(encoder.invoke(backend));
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders could not capture a Wormholes view for its water", failure);
        }
    }

    /** Binds to the Metal Shaders on this class loader. */
    static MetalShadersApi bind() throws ReflectiveOperationException {
        return new MetalShadersApi(MetalShadersApi.class.getClassLoader());
    }

    /** Whether Metal Shaders renders the world into its HDR target this session and its post chain is up. */
    boolean hdrShadingActive() {
        try {
            return shadingOn && hdr && (boolean) postActive.invoke();
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders did not answer whether its shading is active", failure);
        }
    }

    /** Adds a pass redirector at this order; Metal Shaders' own HDR redirect runs at order 0. */
    void addRedirector(int order, Function<RenderPassDescriptor, Object> redirector) {
        Object proxy = Proxy.newProxyInstance(redirectorType.getClassLoader(), new Class<?>[] {redirectorType}, (instance, method, arguments) -> {
            switch (method.getName()) {
                case "redirect":
                    return redirector.apply((RenderPassDescriptor) arguments[0]);
                case "equals":
                    return instance == arguments[0];
                case "hashCode":
                    return System.identityHashCode(instance);
                case "toString":
                    return "Wormholes portal pass redirector";
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        });
        try {
            addRedirector.invoke(order, proxy);
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders rejected the Wormholes pass redirector", failure);
        }
    }

    /** A redirect that keeps the pass on these colour textures but compiles its pipelines for the given formats. */
    Object redirect(long[] colors, float[][] clears, int width, int height, int[] formats) {
        try {
            return redirectFormats.invoke(colors, clears, width, height, formats);
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders rejected a Wormholes pass redirect", failure);
        }
    }

    /** Registers MSL rewrites for a pipeline by name; a null stage keeps the translated source. */
    void register(String pipeline, UnaryOperator<String> vertex, UnaryOperator<String> fragment, List<String> requiredUniforms) {
        try {
            register.invoke(pipeline, newVariant.invoke(vertex, fragment, requiredUniforms));
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders rejected the Wormholes shader variant for " + pipeline, failure);
        }
    }

    long textureHandle(GpuTexture texture) {
        try {
            return (long) textureHandle.invoke(texture);
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders gave no handle for a Wormholes portal texture", failure);
        }
    }

    /** Metal Shaders' shading prelude: ShadeFrame, ShadeLook and its lighting, sky, cloud and fog functions. */
    String prelude() {
        try {
            return (String) prelude.invoke();
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders gave no shading prelude", failure);
        }
    }

    /** A display-referred sRGB colour as the scene radiance Metal Shaders' HDR target stores, with the live look. */
    float[] ldrToHdr(float red, float green, float blue) {
        try {
            return (float[]) ldrToHdr.invoke(look.invoke(), red, green, blue);
        } catch (Throwable failure) {
            throw new IllegalStateException("Metal Shaders could not convert a clear colour", failure);
        }
    }
}
