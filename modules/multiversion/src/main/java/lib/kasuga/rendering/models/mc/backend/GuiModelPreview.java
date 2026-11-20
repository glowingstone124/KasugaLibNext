package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.backend.ui.ModelPreviewCamera;
import lib.kasuga.rendering.models.mc.renderpearl.RenderPearlModelSource;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import lib.kasuga.rendering.models.mc.scene.ModelBounds;
import lib.kasuga.rendering.models.mc.scene.ModelPreviewResources;
import lib.kasuga.rendering.models.mc.typo.KsgMinecraftJsonLoader;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Owns a local RenderPearl model and submits it to Minecraft's depth-buffered GUI renderer. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class GuiModelPreview implements AutoCloseable {
    private final @Nullable Identifier resource;
    private final @Nullable String modelName;
    private @Nullable MinecraftModelSource source;
    private long openedGeneration = -1;
    private volatile View view = new View(0, 0, 1, 0, 0);
    private volatile LayerLayout layers = LayerLayout.ASSEMBLED;
    private @Nullable MinecraftModelSource.Snapshot pickFrame;
    private @Nullable Matrix4f pickMatrix;
    private @Nullable String failure;
    private boolean closed;

    public GuiModelPreview(Identifier resource, @Nullable String modelName) {
        this.resource = Objects.requireNonNull(resource, "resource");
        this.modelName = modelName;
    }

    /** Takes ownership of the supplied source. */
    public GuiModelPreview(MinecraftModelSource source) {
        this.resource = null;
        this.modelName = null;
        this.source = Objects.requireNonNull(source, "source");
    }

    public void setLayers(LayerLayout layers) { this.layers = Objects.requireNonNull(layers, "layers"); }
    public LayerLayout layers() { return layers; }

    @SubscribeEvent
    public static void registerRenderer(RegisterPictureInPictureRenderersEvent event) {
        event.register(PreviewState.class, Renderer::new);
    }

    public void setView(float yaw, float pitch, float zoom) {
        setView(yaw, pitch, zoom, 0, 0);
    }

    /** Angles are in radians and pan is a fraction of the viewport. */
    public void setView(float yaw, float pitch, float zoom, float panX, float panY) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(zoom) || zoom <= 0
                || !Float.isFinite(panX) || !Float.isFinite(panY)) {
            throw new IllegalArgumentException("Invalid model preview rotation, zoom or pan");
        }
        view = new View(yaw, pitch, zoom, panX, panY);
    }

    public boolean render(GuiGraphicsExtractor graphics, float x, float y, int width, int height) {
        float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        return render(graphics, x, y, width, height, partialTick);
    }

    /** Samples the local model with an interpolation fraction in [0, 1]. */
    public boolean render(GuiGraphicsExtractor graphics, float x, float y, int width, int height, float partialTick) {
        RenderSystem.assertOnRenderThread();
        pickFrame = null;
        pickMatrix = null;
        if (closed || width <= 0 || height <= 0) return false;
        refresh();
        if (source == null) return false;

        Matrix3x2f placement = new Matrix3x2f(graphics.pose()).translate(x, y);
        ScreenRectangle scissor = graphics.peekScissorStack();
        ScreenRectangle bounds = PictureInPictureRenderState.getBounds(0, 0, width, height, placement, scissor);
        if (bounds == null) return false;
        MinecraftModelSource.Snapshot snapshot = source.snapshot(layers, partialTick);
        if (snapshot == null) return false;
        View camera = view;
        ModelBounds modelBounds = snapshot.bounds();
        ModelPreviewCamera.Frame frame = ModelPreviewCamera.frame(modelBounds.minimum(), modelBounds.maximum(),
                (float) width / height, camera.yaw(), camera.pitch(), camera.zoom(), camera.panX(), camera.panY());
        float scale = frame.projection().m11() * height * .5f;
        float depthRadius = -.25f / frame.projection().m22();
        pickFrame = snapshot;
        pickMatrix = new Matrix4f(frame.projection()).mul(frame.modelView()).invert();
        graphics.submitPictureInPictureRenderState(
                new PreviewState(width, height, scale, placement, scissor, bounds,
                        frame.modelView(), depthRadius, snapshot, graphics, partialTick));
        return true;
    }

    /** Picks from the last extracted preview using coordinates normalized to its local viewport. */
    public Optional<MinecraftModelSource.BlockHit> pick(double x, double y) {
        RenderSystem.assertOnRenderThread();
        if (pickFrame == null || pickMatrix == null || !(x >= 0 && x <= 1 && y >= 0 && y <= 1)) return Optional.empty();
        float nx = (float) (2 * x - 1);
        float ny = (float) (1 - 2 * y);
        Vector3f from = pickMatrix.transformProject(new Vector3f(nx, ny, -1));
        Vector3f to = pickMatrix.transformProject(new Vector3f(nx, ny, 1));
        return pickFrame.pick(new Vec3(from), new Vec3(to));
    }

    private void refresh() {
        long generation = ModelPreviewResources.generation();
        if (resource == null || generation == openedGeneration) return;
        releaseSource();
        openedGeneration = generation;
        try {
            source = modelName != null && resource.getPath().endsWith(".geo.json")
                    ? KsgMinecraftJsonLoader.openBedrock(resource, modelName, null)
                    : RenderPearlModelSource.open(resource);
            failure = null;
        } catch (IOException | RuntimeException error) {
            failure = error.getMessage();
        }
    }

    public @Nullable ModelInstance instance() {
        return source instanceof RenderPearlModelSource model ? model.instance() : null;
    }
    public @Nullable String failure() { return failure; }
    public boolean isClosed() { return closed; }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true;
        pickFrame = null;
        pickMatrix = null;
        releaseSource();
    }

    private void releaseSource() {
        MinecraftModelSource previous = source;
        source = null;
        if (previous != null) previous.close();
    }

    private record View(float yaw, float pitch, float zoom, float panX, float panY) {}

    private record PreviewState(int x1, int y1, float scale, Matrix3x2fc pose,
                                @Nullable ScreenRectangle scissorArea, ScreenRectangle bounds,
                                Matrix4fc view, float depthRadius, MinecraftModelSource.Snapshot snapshot,
                                Object frameToken, float partialTick) implements PictureInPictureRenderState {
        @Override public int x0() { return 0; }
        @Override public int y0() { return 0; }
    }

    private static final class Renderer extends PictureInPictureRenderer<PreviewState> {
        @Override public Class<PreviewState> getRenderStateClass() { return PreviewState.class; }
        @Override protected String getTextureLabel() { return "Kasuga model preview"; }
        @Override protected float getTranslateY(int height, int guiScale) { return height * .5f; }

        @Override protected void renderToTexture(PreviewState state, PoseStack pose, SubmitNodeCollector nodes) {
            Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
            // Minecraft's PIP depth planes are fixed at ±1000, independent of zoom and GUI scale.
            float scaledRadius = pose.last().pose().m00() * state.depthRadius();
            if (scaledRadius > 500f) pose.scale(1, 1, 500f / scaledRadius);
            Matrix4f targetPose = pose.last().pose();
            MinecraftModelSource.Viewpoint viewpoint = new MinecraftModelSource.Viewpoint(
                    new Vec3(targetPose.m30(), targetPose.m31(), Math.min(scaledRadius, 500f) * 4.0),
                    new Vector3f(0, 0, -1), new Vector3f(0, -1, 0));
            pose.rotateDegrees(Axis.XP, 180f);
            pose.mulPose(state.view());
            state.snapshot().render(new MinecraftModelSource.Context(
                    pose, nodes, state.frameToken(), state.partialTick(), LightCoordsUtil.FULL_BRIGHT, viewpoint));
        }
    }
}
