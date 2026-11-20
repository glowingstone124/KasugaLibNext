package lib.kasuga.rendering.effect.builtin.blackhole;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.ClientResourceReloadFinishEvent;
import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.rendering.effect.pipeline.WorldProjection;
import lib.kasuga.rendering.effect.post.PostProcessContext;
import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraph;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphPassDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistry;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphTarget;
import lib.kasuga.rendering.effect.shader.RenderPearlFullscreenShader;
import lib.kasuga.shader.ShaderProgram;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Batched world-space lenses composited through the shared post-process graph. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class BlackHoleEffects {
    public static final int MAX_VISIBLE_BLACK_HOLES = 8;
    private static final int FLOATS_PER_HOLE = 16;
    private static final Identifier OWNER = id("black_hole");
    private static final Identifier GRAPH = id("black_hole_graph");
    private static final Identifier SCENE_TEXTURE = id("black_hole_scene");
    private static final Identifier VISIBLE = id("black_hole_visible");
    private static final PostProcessGraphTarget MAIN = PostProcessGraphTarget.main();
    private static final PostProcessGraphTarget SCENE = PostProcessGraphTarget.managed(SCENE_TEXTURE);
    private static final Map<Identifier, Registration> EFFECTS = new HashMap<>();
    private static final Comparator<Projected> PROJECTED_ORDER = Comparator.comparingDouble(Projected::radius).reversed()
            .thenComparing(value -> value.effect().id());
    private static List<BlackHoleEffect> snapshot = List.of();
    private static RenderPearlFullscreenShader shader;
    private static boolean attemptedCompile;

    private BlackHoleEffects() {}

    /** Register from the client render thread. Closing a replaced handle has no effect on its replacement. */
    public static Registration register(BlackHoleEffect effect) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(effect, "effect");
        ensureGraph();
        Registration registration = new Registration(effect);
        EFFECTS.put(effect.id(), registration);
        refreshSnapshot();
        return registration;
    }

    public static void put(BlackHoleEffect effect) { register(effect); }

    public static boolean remove(Identifier id) {
        RenderSystem.assertOnRenderThread();
        if (EFFECTS.remove(id) == null) return false;
        refreshSnapshot();
        return true;
    }

    public static void clear() {
        RenderSystem.assertOnRenderThread();
        EFFECTS.clear();
        snapshot = List.of();
    }

    public static int size() { return snapshot.size(); }

    public static List<BlackHoleEffect> snapshot() { return snapshot; }

    @SubscribeEvent
    public static void reload(ClientResourceReloadFinishEvent event) { releaseShader(); }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel) clear();
    }

    @SubscribeEvent
    public static void shutdown(GameShuttingDownEvent event) { releaseShader(); }

    private static void releaseShader() {
        attemptedCompile = false;
        if (shader == null) return;
        shader.close();
        shader = null;
    }

    private static boolean compileIfNeeded() {
        if (attemptedCompile) return shader != null;
        attemptedCompile = true;
        ShaderProgram program = BlackHoleShaderProvider.program();
        try {
            shader = RenderPearlFullscreenShader.compile(program, Minecraft.getInstance().getResourceManager(), ColorTargetState.DEFAULT);
        } catch (IOException | RuntimeException exception) {
            LogUtils.getLogger().error("Unable to compile the black-hole shader", exception);
        }
        return shader != null;
    }

    private static void ensureGraph() {
        if (PostProcessGraphRegistry.isRegistered(GRAPH)) return;
        PostProcessGraph graph = PostProcessGraph.builder(GRAPH)
                .target(PostProcessTargetDescriptor.builder(SCENE_TEXTURE).screenScale(1)
                        .filter(PostProcessTargetDescriptor.TextureFilter.LINEAR).useDepth(true).build())
                .prepare((context, frame) -> {
                    if (snapshot.isEmpty()) return false;
                    List<Projected> visible = project(context);
                    if (visible.isEmpty() || !compileIfNeeded()) return false;
                    frame.put(VISIBLE, new Visible(visible));
                    return true;
                })
                .pass(PostProcessGraphPassDescriptor.builder(id("black_hole/capture"), context -> {
                    context.copyColor(MAIN, SCENE, false);
                    context.copyDepth(MAIN, SCENE);
                })
                        .readsMain().writes(SCENE).build())
                .pass(PostProcessGraphPassDescriptor.builder(id("black_hole/composite"), context -> {
                    List<Projected> visible = context.frame().require(VISIBLE, Visible.class).holes();
                    RenderTarget target = context.write(MAIN);
                    float time = (Minecraft.getInstance().level.getGameTime() + context.raw().partialTick()) / 20f;
                    context.fullscreen(MAIN, shader, false, Map.of(
                            "HoleCount", new double[]{visible.size()}, "HoleData", parameters(visible),
                            "ScreenSize", new double[]{target.width, target.height}, "Time", new double[]{time},
                            "ReversedDepth", new double[]{1}), pass -> {
                        context.bindColor(pass, "SceneSampler", SCENE, 0);
                        context.bindDepth(pass, "DepthSampler", SCENE);
                    });
                }).reads(SCENE).writesMain().build())
                .build();
        PostProcessGraphRegistry.register(OWNER, 1000, DuplicatePolicy.FAIL, graph);
    }

    private static List<Projected> project(PostProcessContext context) {
        RenderTarget target = context.mainTarget();
        Vec3 camera = context.camera().pos;
        Matrix4fc projection = WorldProjection.current();
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        float aspect = (float) target.width / target.height;
        List<Projected> visible = new ArrayList<>();
        Vector4f clip = new Vector4f();
        for (BlackHoleEffect effect : snapshot) {
            Vec3 position = effect.position();
            clip.set((float) (position.x - camera.x), (float) (position.y - camera.y), (float) (position.z - camera.z), 1);
            context.camera().viewRotationMatrix.transform(clip);
            projection.transform(clip);
            if (clip.w <= .001f || !Float.isFinite(clip.w)) continue;
            float inverseW = 1 / clip.w;
            float x = clip.x * inverseW * .5f + .5f;
            float y = clip.y * inverseW * .5f + .5f;
            float depth = clip.z * inverseW;
            if (!zeroToOne) depth = depth * .5f + .5f;
            float radius = Math.abs(projection.m11() * effect.eventHorizonRadius() * inverseW) * .5f;
            if (!Float.isFinite(radius) || radius <= .00001f || !(depth >= 0 && depth <= 1)) continue;
            float influence = radius * effect.influenceRadius();
            float horizontal = influence / aspect;
            if (x + horizontal < 0 || x - horizontal > 1 || y + influence < 0 || y - influence > 1) continue;
            visible.add(new Projected(effect, x, y, radius, depth));
        }
        visible.sort(PROJECTED_ORDER);
        if (visible.size() > MAX_VISIBLE_BLACK_HOLES) return visible.subList(0, MAX_VISIBLE_BLACK_HOLES);
        return visible;
    }

    private static double[] parameters(List<Projected> visible) {
        double[] parameters = new double[MAX_VISIBLE_BLACK_HOLES * FLOATS_PER_HOLE];
        int index = 0;
        for (Projected projected : visible) {
            BlackHoleEffect effect = projected.effect();
            parameters[index++] = projected.x();
            parameters[index++] = projected.y();
            parameters[index++] = projected.radius();
            parameters[index++] = projected.depth();
            parameters[index++] = effect.influenceRadius();
            parameters[index++] = effect.distortionStrength();
            parameters[index++] = effect.accretionRadius();
            parameters[index++] = effect.accretionWidth();
            parameters[index++] = effect.glowStrength();
            parameters[index++] = effect.chromaticAberration();
            parameters[index++] = effect.rotationSpeed();
            parameters[index++] = effect.depthTest() ? 1 : 0;
            parameters[index++] = effect.glowColor().red();
            parameters[index++] = effect.glowColor().green();
            parameters[index++] = effect.glowColor().blue();
            parameters[index++] = effect.accretionDiskTilt();
        }
        return parameters;
    }

    private static void refreshSnapshot() {
        List<BlackHoleEffect> effects = new ArrayList<>(EFFECTS.size());
        for (Registration value : EFFECTS.values()) effects.add(value.effect);
        effects.sort(Comparator.comparing(BlackHoleEffect::id));
        snapshot = List.copyOf(effects);
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath(KasugaLib.MODID, path); }

    private record Projected(BlackHoleEffect effect, float x, float y, float radius, float depth) {}
    private record Visible(List<Projected> holes) {}

    public static final class Registration implements AutoCloseable {
        private BlackHoleEffect effect;

        private Registration(BlackHoleEffect effect) { this.effect = effect; }

        public Identifier id() { return effect.id(); }
        public BlackHoleEffect effect() { return effect; }

        public void update(BlackHoleEffect value) {
            RenderSystem.assertOnRenderThread();
            if (EFFECTS.get(id()) != this) throw new IllegalStateException("Black-hole registration is inactive");
            if (!id().equals(value.id())) throw new IllegalArgumentException("An effect registration cannot change its ID");
            effect = value;
            refreshSnapshot();
        }

        @Override
        public void close() {
            RenderSystem.assertOnRenderThread();
            if (EFFECTS.remove(id(), this)) refreshSnapshot();
        }
    }
}
