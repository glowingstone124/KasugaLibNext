package lib.kasuga.rendering.effect.builtin.cloud;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.cloud.SkyCloudField;
import lib.kasuga.rendering.cloud.SkyCloudLayer;
import lib.kasuga.rendering.cloud.SkyCloudRendering;
import lib.kasuga.rendering.cloud.SkyCloudSettings;
import lib.kasuga.rendering.cloud.SkyCloudTemporal;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.post.WorldPostProcess;
import lib.kasuga.rendering.output.mc.CameraRenderScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

final class SkyCloudRenderer implements AutoCloseable {
    private static final String SKY_PARAMS = "SkyParameters";
    private static final String HISTORY_PARAMS = "HistoryParameters";
    private static final int SKY_BYTES = 64 + (13 + 4 * SkyCloudField.MAX_LAYERS) * 16;
    private static final int HISTORY_BYTES = 128 + 3 * 16;
    private static final int MAX_WEATHER_CACHE = 8;
    private static final RenderPipeline MARCH = pipeline("sky", SKY_PARAMS, true,
            "SceneDepth", "NoiseVolume", "ShapeVolume", "WeatherLayout", "WeatherDimensions", "WeatherLayers");
    private static final RenderPipeline HISTORY = pipeline("sky_history", HISTORY_PARAMS, true,
            "CurrentColor", "CurrentMetadata", "HistoryColor", "HistoryMetadata");
    private static final RenderPipeline COMPOSITE = pipeline("sky_composite", SKY_PARAMS, false, "CloudColor", "SceneColor", "SceneDepth");
    private final CloudVolumeAtlas atlas;
    private final LinkedHashMap<SkyCloudField.Key, WeatherTextures> weather = new LinkedHashMap<>(8, .75f, true);
    private final LinkedHashMap<String, ViewTargets> views = new LinkedHashMap<>();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f inverseProjection = new Matrix4f();
    private final Vector3f sun = new Vector3f();
    private final Vector3f sunlight = new Vector3f();
    private final Vector3f ambient = new Vector3f();
    private final Vector3f atmosphere = new Vector3f();

    SkyCloudRenderer(CloudVolumeAtlas atlas) { this.atlas = atlas; }

    static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(MARCH);
        event.registerPipeline(HISTORY);
        event.registerPipeline(COMPOSITE);
    }

    void render(WorldRenderPipelineContext context) {
        if (!CloudEffects.isEnabled() || !CloudEffects.isSkyEnabled() || !CloudVolumeAtlas.ready()) return;
        Minecraft client = Minecraft.getInstance();
        LevelRenderState level = context.event().getLevelRenderState();
        if (!Float.isFinite(level.cloudHeight) || client.level == null) return;
        Vec3 eye = context.camera().pos;
        SkyCloudField.Frame frame = CloudEffects.sky().sample(eye.x, eye.y, eye.z, context.partialTick(),
                client.level.getRainLevel(context.partialTick()));
        String viewId = CameraRenderScope.currentViewId();
        if (frame.coverage() == 0 || frame.settings().rendering().extinction() == 0) {
            ViewTargets targets = views.get(viewId);
            if (targets != null) targets.clearHistory();
            return;
        }
        RenderTarget main = client.gameRenderer.mainRenderTarget();
        if (main.getDepthTextureView() == null) return;
        atlas.prepare();
        updateLighting(level);
        viewProjection.set(context.projectionMatrix()).mul(context.camera().viewRotationMatrix);
        inverseProjection.set(viewProjection).invert();
        SkyCloudRendering optics = frame.settings().rendering();
        int divisor = optics.resolutionDivisor(frame.settings().quality());
        int width = Math.ceilDiv(main.width, divisor);
        int height = Math.ceilDiv(main.height, divisor);
        ViewTargets targets = views.computeIfAbsent(viewId, ignored -> new ViewTargets());
        targets.resize(width, height);
        SkyTarget current = targets.current;
        boolean useHistory = optics.temporal().historyWeight() > 0
                && (optics.debug() == SkyCloudRendering.DebugView.NONE || optics.debug() == SkyCloudRendering.DebugView.HISTORY_VALIDITY);
        if (useHistory && targets.history == null) targets.history = new History(width, height);
        else if (!useHistory) targets.clearHistory();
        History history = targets.history;
        GpuBufferSlice parameters = uploadSky(frame, width, height, history);
        WeatherTextures textures = weather(frame.window());
        try (RenderPass pass = pass(current, MARCH)) {
            pass.setUniform(SKY_PARAMS, parameters);
            bind(pass, "SceneDepth", main.getDepthTextureView(), FilterMode.NEAREST);
            bind(pass, "NoiseVolume", atlas.noise(), FilterMode.LINEAR);
            bind(pass, "ShapeVolume", atlas.shapes(), FilterMode.LINEAR);
            bind(pass, "WeatherLayout", textures.layout.view, FilterMode.NEAREST);
            bind(pass, "WeatherDimensions", textures.dimensions.view, FilterMode.NEAREST);
            bind(pass, "WeatherLayers", textures.layers.view, FilterMode.LINEAR);
            pass.draw(3, 1, 0, 0);
        }
        SkyTarget resolved = useHistory ? resolve(frame, eye, current, history) : current;
        targets.composite.draw(main, COMPOSITE, (pass, scene, depth) -> {
            pass.setUniform(SKY_PARAMS, parameters);
            bind(pass, "SceneColor", scene, FilterMode.NEAREST);
            bind(pass, "SceneDepth", depth, FilterMode.NEAREST);
            bind(pass, "CloudColor", resolved.color.getColorTextureView(), FilterMode.NEAREST);
        });
    }

    private void updateLighting(LevelRenderState state) {
        float angle = state.skyRenderState.sunAngle;
        sun.set(-(float) Math.sin(angle), (float) Math.cos(angle), 0);
        float twilight = Math.clamp((sun.y + .06f) / .18f, 0, 1);
        float strength = twilight * twilight * (3 - 2 * twilight) * state.skyRenderState.rainBrightness;
        float warm = Math.clamp(sun.y / .28f, 0, 1);
        sunlight.set(.98f - .08f * warm, .42f + .42f * warm, .19f + .55f * warm).mul(strength);
        int color = state.cloudColor;
        ambient.set(ARGB.red(color), ARGB.green(color), ARGB.blue(color)).mul(.32f / 255);
        Vector3fc sky = state.skyRenderState.skyColor;
        if (sky == null) atmosphere.set(.22f, .42f, .67f);
        else atmosphere.set(sky);
    }

    private GpuBufferSlice uploadSky(SkyCloudField.Frame frame, int width, int height, History history) {
        SkyCloudSettings settings = frame.settings();
        SkyCloudRendering optics = settings.rendering();
        List<SkyCloudLayer> layers = settings.layers();
        int stormLayer = -1;
        for (int index = 0; index < layers.size(); index++) {
            if (layers.get(index).type() == SkyCloudLayer.Type.CUMULUS) {
                stormLayer = index;
                break;
            }
        }
        try (GpuBufferSlice.MappedView mapped = allocate(SKY_BYTES)) {
            float far = farClipDepth();
            float jitter = history == null ? 0 : phase(history.frames * .61803398875);
            Std140Builder out = Std140Builder.intoBuffer(mapped.data()).putMat4f(inverseProjection)
                    .putVec4(1 - far, far, 0, 0)
                    .putVec4(frame.originX(), frame.baseY(), frame.originZ(), optics.atmosphereDistance())
                    .putVec4(sun.x, sun.y, sun.z, 0).putVec4(sunlight.x, sunlight.y, sunlight.z, 0)
                    .putVec4(ambient.x, ambient.y, ambient.z, 0)
                    .putVec4(phase(frame.timeSeconds() * .004), phase(-frame.timeSeconds() * .0014), 0, jitter)
                    .putVec4(atmosphere.x, atmosphere.y, atmosphere.z, 0)
                    .putVec4(settings.cellSize(), settings.maxHeight(), frame.coverage(), settings.distance())
                    .putVec4(optics.extinction(), optics.erosion(), optics.detailFrequency(), optics.ambientStrength())
                    .putVec4(width, height, .49f / width, .49f / height)
                    .putIVec4(SkyCloudField.GRID_SIZE, Math.min(512, optics.viewSteps(settings.quality()) * (optics.adaptive() ? 1 : 2)),
                            layers.size(), stormLayer)
                    .putIVec4(optics.lightSteps(settings.quality()), optics.adaptive() ? 1 : 0, optics.debug().ordinal(),
                            optics.colorSpace() == SkyCloudRendering.ColorSpace.DISPLAY_SRGB ? 1 : 0)
                    .putVec4(optics.forwardAnisotropy(), optics.backwardAnisotropy(), optics.backwardWeight(), optics.multipleScattering());
            for (SkyCloudLayer layer : layers) {
                out.putVec4(frame.baseY() + layer.baseHeight() - settings.baseHeight(), layer.thickness(),
                        layer.detailScale(), layer.type().ordinal());
            }
            padLayers(out, layers.size());
            for (SkyCloudLayer layer : layers) out.putVec4(layer.coverageBias(), layer.density(), 0, 0);
            padLayers(out, layers.size());
            putLayerPhases(out, frame, false);
            putLayerPhases(out, frame, true);
            return mapped.slice().slice(0, SKY_BYTES);
        }
    }

    private static void putLayerPhases(Std140Builder out, SkyCloudField.Frame frame, boolean detail) {
        SkyCloudSettings settings = frame.settings();
        List<SkyCloudLayer> layers = settings.layers();
        SkyCloudField.Key key = frame.window().key();
        double worldX = key.x() * (double) settings.cellSize() - frame.originX();
        double worldZ = key.z() * (double) settings.cellSize() - frame.originZ();
        for (int index = 0; index < layers.size(); index++) {
            SkyCloudLayer layer = layers.get(index);
            boolean cirrus = layer.type() == SkyCloudLayer.Type.CIRRUS;
            double x = (cirrus ? worldX * .25 + worldZ * .5 : worldX) / layer.detailScale() + index * .173 + frame.timeSeconds() * .001;
            double y = frame.timeSeconds() * -.0007 + index * .31;
            double z = worldZ * (cirrus ? 2.7 : 1) / layer.detailScale() + index * .419;
            if (detail) {
                float frequency = settings.rendering().detailFrequency();
                x *= frequency * (cirrus ? .5 : 1);
                y *= frequency;
                z *= frequency * (cirrus ? 2 : 1);
            }
            out.putVec4(phase(x), phase(y), phase(z), 0);
        }
        padLayers(out, layers.size());
    }

    private static void padLayers(Std140Builder out, int used) {
        for (int index = used; index < SkyCloudField.MAX_LAYERS; index++) out.putVec4(0, 0, 0, 0);
    }

    private SkyTarget resolve(SkyCloudField.Frame frame, Vec3 eye, SkyTarget current, History history) {
        SkyCloudTemporal controls = frame.settings().rendering().temporal();
        double elapsed = frame.timeSeconds() - history.time;
        Vec3 delta = eye.subtract(history.eye);
        boolean valid = frame.settings().equals(history.settings) && elapsed >= 0 && elapsed <= controls.maxTimeGap()
                && delta.length() <= controls.maxCameraMovement() && Math.abs(frame.coverage() - history.coverage) < .005
                && sun.distanceSquared(history.sun) < .001f && sunlight.distanceSquared(history.sunlight) < .001f
                && ambient.distanceSquared(history.ambient) < .001f && atmosphere.distanceSquared(history.atmosphere) < .001f;
        SkyTarget previous = history.targets[history.read];
        SkyTarget next = history.targets[1 - history.read];
        GpuBufferSlice parameters;
        try (GpuBufferSlice.MappedView mapped = allocate(HISTORY_BYTES)) {
            Std140Builder.intoBuffer(mapped.data()).putMat4f(inverseProjection).putMat4f(history.viewProjection)
                    .putVec4((float) (delta.x - frame.settings().windX() * elapsed), (float) delta.y,
                            (float) (delta.z - frame.settings().windZ() * elapsed), controls.historyWeight())
                    .putVec4(current.color.width, current.color.height, controls.relativeDepthTolerance(), farClipDepth())
                    .putIVec4(valid ? 1 : 0, frame.settings().rendering().debug() == SkyCloudRendering.DebugView.HISTORY_VALIDITY ? 1 : 0, 0, 0);
            parameters = mapped.slice().slice(0, HISTORY_BYTES);
        }
        try (RenderPass pass = pass(next, HISTORY)) {
            pass.setUniform(HISTORY_PARAMS, parameters);
            bind(pass, "CurrentColor", current.color.getColorTextureView(), FilterMode.NEAREST);
            bind(pass, "CurrentMetadata", current.metadata.getColorTextureView(), FilterMode.NEAREST);
            bind(pass, "HistoryColor", previous.color.getColorTextureView(), FilterMode.LINEAR);
            bind(pass, "HistoryMetadata", previous.metadata.getColorTextureView(), FilterMode.NEAREST);
            pass.draw(3, 1, 0, 0);
        }
        history.read = 1 - history.read;
        history.frames++;
        history.settings = frame.settings();
        history.time = frame.timeSeconds();
        history.coverage = frame.coverage();
        history.eye = eye;
        history.sun.set(sun);
        history.sunlight.set(sunlight);
        history.ambient.set(ambient);
        history.atmosphere.set(atmosphere);
        history.viewProjection.set(viewProjection);
        return next;
    }

    private WeatherTextures weather(SkyCloudField.Window window) {
        WeatherTextures textures = weather.get(window.key());
        if (textures != null) return textures;
        textures = new WeatherTextures(window);
        weather.put(window.key(), textures);
        if (weather.size() > MAX_WEATHER_CACHE) weather.pollFirstEntry().getValue().close();
        return textures;
    }

    void clearHistory() {
        for (ViewTargets targets : views.values()) targets.clearHistory();
    }

    void releaseView(String viewId) {
        ViewTargets targets = views.remove(viewId);
        if (targets != null) targets.close();
    }

    @Override public void close() {
        for (ViewTargets targets : views.values()) targets.close();
        views.clear();
        for (WeatherTextures textures : weather.values()) textures.close();
        weather.clear();
    }

    private static final class ViewTargets implements AutoCloseable {
        final WorldPostProcess composite = new WorldPostProcess("Kasuga sky cloud");
        SkyTarget current;
        History history;

        void resize(int width, int height) {
            if (current != null && current.color.width == width && current.color.height == height) return;
            SkyTarget replacement = new SkyTarget(width, height);
            if (current != null) current.close();
            current = replacement;
            clearHistory();
        }

        void clearHistory() {
            if (history != null) history.close();
            history = null;
        }

        @Override public void close() {
            clearHistory();
            if (current != null) current.close();
            composite.close();
        }
    }

    private static void bind(RenderPass pass, String name, GpuTextureView view, FilterMode filter) {
        pass.setUniform(name, view, RenderSystem.getSamplerCache().getClampToEdge(filter));
    }

    private static GpuBufferSlice.MappedView allocate(int size) {
        GpuDevice device = RenderSystem.getDevice();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        return device.createCommandEncoder().transientMemory().allocateGpuMapped(size, alignment, GpuBuffer.USAGE_UNIFORM);
    }

    private static float farClipDepth() { return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 0 : -1; }
    private static float phase(double value) { return (float) (value - Math.floor(value)); }

    private static RenderPass pass(SkyTarget target, RenderPipeline pipeline) {
        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> pipeline.getLocation().toString())
                .withColorAttachment(target.color.getColorTextureView()).withColorAttachment(target.metadata.getColorTextureView()).build();
        RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor);
        try {
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            return pass;
        } catch (RuntimeException | Error failure) {
            pass.close();
            throw failure;
        }
    }

    private static RenderPipeline pipeline(String name, String parameters, boolean mrt, String... samplers) {
        BindGroupLayout.Builder bindings = BindGroupLayout.builder().withUniform(parameters, UniformType.UNIFORM_BUFFER);
        for (String sampler : samplers) bindings.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
        RenderPipeline.Builder builder = RenderPipeline.builder().withLocation(id("pipeline/cloud/" + name))
                .withVertexShader(id("core/post/fullscreen")).withFragmentShader(id("core/cloud/" + name))
                .withBindGroupLayout(bindings.build()).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
        if (mrt) {
            builder.withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT, ColorTargetState.WRITE_ALL))
                    .withColorTargetState(1, new ColorTargetState(Optional.empty(), GpuFormat.RG32_FLOAT, ColorTargetState.WRITE_ALL));
        } else builder.withColorTargetState(ColorTargetState.DEFAULT);
        return builder.build();
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath(KasugaLib.MODID, path); }

    private static final class SkyTarget implements AutoCloseable {
        final TextureTarget color;
        final TextureTarget metadata;

        SkyTarget(int width, int height) {
            color = new TextureTarget("Kasuga sky cloud color", width, height, GpuFormat.RGBA16_FLOAT, null);
            try {
                metadata = new TextureTarget("Kasuga sky cloud metadata", width, height, GpuFormat.RG32_FLOAT, null);
            } catch (RuntimeException | Error failure) {
                color.destroyBuffers();
                throw failure;
            }
        }

        @Override public void close() {
            color.destroyBuffers();
            metadata.destroyBuffers();
        }
    }

    private static final class History implements AutoCloseable {
        final SkyTarget[] targets = new SkyTarget[2];
        final Matrix4f viewProjection = new Matrix4f();
        final Vector3f sun = new Vector3f();
        final Vector3f sunlight = new Vector3f();
        final Vector3f ambient = new Vector3f();
        final Vector3f atmosphere = new Vector3f();
        SkyCloudSettings settings;
        Vec3 eye = Vec3.ZERO;
        double time;
        float coverage;
        int read;
        long frames;

        History(int width, int height) {
            targets[0] = new SkyTarget(width, height);
            try {
                targets[1] = new SkyTarget(width, height);
            } catch (RuntimeException | Error failure) {
                targets[0].close();
                throw failure;
            }
        }

        @Override public void close() {
            for (SkyTarget target : targets) target.close();
        }
    }

    private static final class WeatherTextures implements AutoCloseable {
        WeatherTexture layout;
        WeatherTexture dimensions;
        WeatherTexture layers;

        WeatherTextures(SkyCloudField.Window window) {
            try {
                layout = new WeatherTexture("Kasuga storm layout", window.layout(), 1);
                dimensions = new WeatherTexture("Kasuga storm dimensions", window.dimensions(), 1);
                layers = new WeatherTexture("Kasuga weather layers", window.weatherLayers(), SkyCloudField.MAX_LAYERS);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        @Override public void close() {
            if (layout != null) layout.close();
            if (dimensions != null) dimensions.close();
            if (layers != null) layers.close();
        }
    }

    private static final class WeatherTexture implements AutoCloseable {
        final GpuTexture texture;
        final GpuTextureView view;

        WeatherTexture(String label, float[] values, int layers) {
            GpuDevice device = RenderSystem.getDevice();
            int side = SkyCloudField.GRID_SIZE;
            int height = side * layers;
            texture = device.createTexture(label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA32_FLOAT, side, height, 1, 1);
            try {
                ByteBuffer data = MemoryUtil.memAlloc(values.length * Float.BYTES);
                try {
                    data.asFloatBuffer().put(values);
                    device.createCommandEncoder().writeToTexture(texture, data, 0, 0, 0, 0, side, height);
                } finally {
                    MemoryUtil.memFree(data);
                }
                view = device.createTextureView(texture);
            } catch (RuntimeException | Error failure) {
                texture.close();
                throw failure;
            }
        }

        @Override public void close() {
            view.close();
            texture.close();
        }
    }
}
