package lib.kasuga.rendering.models.mc.backend.ui;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.WindowRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fStack;

/** A panel's color/depth attachments, rendered with the native GUI renderer. */
final class WorldUiCanvas implements AutoCloseable {
    private @Nullable TextureTarget target;
    private boolean painting;
    private boolean closed;

    @Nullable GpuTextureView paint(Painter painter, Renderable renderable, ScreenRectangle area,
                                   int mouseX, int mouseY, float delta) {
        if (closed) return null;
        if (painting) throw new IllegalStateException("Recursive world UI canvas draw");
        painting = true;
        try {
            Minecraft client = Minecraft.getInstance();
            int maxSize = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize();
            int scale = Math.min(client.getWindow().getGuiScale(), maxSize / Math.max(area.width(), area.height()));
            if (scale < 1) throw new IllegalArgumentException("World UI panel exceeds the device texture dimensions");
            int width = area.width() * scale;
            int height = area.height() * scale;
            if (target == null) {
                target = new TextureTarget("Kasuga world UI", width, height, GpuFormat.RGBA8_UNORM,
                        client.gameRenderer.mainRenderTarget().getDepthTexture().getFormat());
            } else if (target.width != width || target.height != height) {
                target.resize(width, height);
            }
            WindowRenderState viewport = new WindowRenderState();
            viewport.width = width;
            viewport.height = height;
            viewport.guiScale = scale;
            viewport.appropriateLineWidth = 1;
            painter.render(client, this, renderable, area, mouseX, mouseY, delta, target, viewport);
            return closed ? null : target.getColorTextureView();
        } finally {
            painting = false;
            if (closed) release();
        }
    }

    @Override public void close() {
        closed = true;
        if (!painting) release();
    }

    private void release() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }

    /** Shared extraction and GUI staging buffers. Each panel retains its own attachment. */
    static final class Painter implements AutoCloseable {
        private final GuiRenderState state = new GuiRenderState();
        private final GuiRenderer renderer;

        Painter(GameRenderer game) {
            renderer = new GuiRenderer(state, game.featureRenderDispatcher(),
                    ((GuiCanvasAccess.Factory) game).kasuga$pictureInPictureFactories());
        }

        private void render(Minecraft client, WorldUiCanvas canvas, Renderable renderable, ScreenRectangle area,
                            int mouseX, int mouseY, float delta, TextureTarget target, WindowRenderState viewport) {
            CanvasGraphics graphics = CanvasGraphics.create(client, state, area, mouseX, mouseY);
            graphics.pose().translate(-area.left(), -area.top());
            GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
            ProjectionType projectionType = RenderSystem.getProjectionType();
            GpuBufferSlice lights = RenderSystem.getShaderLights();
            Matrix4fStack view = RenderSystem.getModelViewStack();
            view.pushMatrix().identity();
            try {
                renderable.extractRenderState(graphics, mouseX, mouseY, delta);
                graphics.extractDeferredElements(mouseX, mouseY, delta);
                if (canvas.closed) return;
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                        target.getColorTexture(), GuiRenderer.CLEAR_COLOR, target.getDepthTexture(), 0.0);
                client.gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);
                ((GuiCanvasAccess.Factory) client.gameRenderer).kasuga$withGuiLighting(
                        () -> ((GuiCanvasAccess) renderer).kasuga$renderCanvas(target, viewport));
            } finally {
                state.reset();
                view.popMatrix();
                RenderSystem.setShaderLights(lights);
                RenderSystem.setProjectionMatrix(projection, projectionType);
            }
        }

        @Override public void close() { renderer.close(); }
    }

    private static final class CanvasGraphics extends GuiGraphicsExtractor {
        // The vanilla constructor queries the virtual size while building its initial scissor.
        private static final ThreadLocal<ScreenRectangle> CONSTRUCTING = new ThreadLocal<>();
        private final int width;
        private final int height;

        static CanvasGraphics create(Minecraft client, GuiRenderState state, ScreenRectangle area, int mouseX, int mouseY) {
            CONSTRUCTING.set(area);
            try {
                return new CanvasGraphics(client, state, area, mouseX, mouseY);
            } finally {
                CONSTRUCTING.remove();
            }
        }

        private CanvasGraphics(Minecraft client, GuiRenderState state, ScreenRectangle area, int mouseX, int mouseY) {
            super(client, state, mouseX, mouseY);
            width = area.width();
            height = area.height();
        }

        @Override public int guiWidth() { return width > 0 ? width : CONSTRUCTING.get().width(); }
        @Override public int guiHeight() { return height > 0 ? height : CONSTRUCTING.get().height(); }
        @Override public void blurBeforeThisStratum() { nextStratum(); }
    }
}
