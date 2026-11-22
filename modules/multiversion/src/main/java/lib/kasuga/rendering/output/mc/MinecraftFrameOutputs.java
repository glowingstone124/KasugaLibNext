package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.FrameOutputRouter;
import lib.kasuga.rendering.output.FrameOutputTarget;
import lib.kasuga.rendering.output.OutputFrame;
import org.joml.Vector4f;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.function.Consumer;

/** Completed RenderPearl views shared among render-thread consumers. */
public final class MinecraftFrameOutputs {
    public static final String MAIN_VIEW = "minecraft:main";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final FrameOutputRouter<GpuTextureView> ROUTER = new FrameOutputRouter<>(
            RenderSystem::assertOnRenderThread, Snapshot::new);
    private static TextureTarget blank;

    private MinecraftFrameOutputs() {}

    public static boolean hasOutputs(String viewId) {
        return ROUTER.hasOutputs(viewId);
    }

    public static FrameOutputRouter<GpuTextureView>.Registration open(FrameOutputMode mode,
                                                                      Consumer<OutputFrame<GpuTextureView>> consumer) {
        return open(MAIN_VIEW, mode, consumer);
    }

    public static FrameOutputRouter<GpuTextureView>.Registration open(String viewId, FrameOutputMode mode,
                                                                      Consumer<OutputFrame<GpuTextureView>> consumer) {
        return ROUTER.register(viewId, mode, consumer);
    }

    /** Copies the completed color attachment in its original format after all render passes close. */
    public static FrameOutputRouter.Delivery publish(String viewId, RenderTarget source) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(source, "source");
        FrameOutputRouter.Delivery delivery = ROUTER.publishCopy(viewId, source.width, source.height, source::getColorTextureView);
        for (Exception failure : delivery.failures()) {
            LOGGER.error("Frame output failed and was detached for view {}", viewId, failure);
        }
        return delivery;
    }

    /** Called after GUI composition, including frames rendered without an acquired surface. */
    public static boolean publishMain(RenderTarget source) {
        boolean exclusive = hasOutputs(MAIN_VIEW) && publish(MAIN_VIEW, source).suppressScreen();
        if (!exclusive) releaseBlank();
        return exclusive;
    }

    /** The swapchain still requires a blit before presenting its acquired image. */
    public static GpuTextureView blankPresentation(GpuSurface surface, GpuTextureView fallback) {
        GpuSurface.Configuration size = surface.currentConfiguration().orElse(null);
        if (size == null) return fallback;
        GpuFormat format = fallback.texture().getFormat();
        if (matches(blank, size.width(), size.height(), format)) {
            return blank.getColorTextureView();
        }
        TextureTarget replacement = null;
        try {
            replacement = new TextureTarget("Kasuga exclusive output", size.width(), size.height(), format, null);
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(
                    replacement.getColorTexture(), new Vector4f(0, 0, 0, 1));
        } catch (RuntimeException failure) {
            if (replacement != null) replacement.destroyBuffers();
            LOGGER.error("Could not blank the exclusive output surface", failure);
            return fallback;
        }
        releaseBlank();
        blank = replacement;
        return blank.getColorTextureView();
    }

    public static void shutdown() {
        releaseBlank();
        try {
            ROUTER.close();
        } catch (Exception failure) {
            LOGGER.error("Could not release all frame outputs", failure);
        }
    }

    private static void releaseBlank() {
        if (blank == null) return;
        blank.destroyBuffers();
        blank = null;
    }

    private static boolean matches(TextureTarget target, int width, int height, GpuFormat format) {
        return target != null && target.width == width && target.height == height
                && target.getColorTexture().getFormat() == format;
    }

    private static final class Snapshot implements FrameOutputTarget.Copying<GpuTextureView> {
        private TextureTarget target;

        @Override
        public GpuTextureView captureFrom(GpuTextureView source, int width, int height) {
            GpuTexture color = source.texture();
            GpuFormat format = color.getFormat();
            if (!matches(target, width, height, format)) {
                TextureTarget replacement = new TextureTarget("Kasuga frame output", width, height, format, null);
                close();
                target = replacement;
            }
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                    color, target.getColorTexture(), 0, 0, 0, 0, 0, width, height);
            return target.getColorTextureView();
        }

        @Override
        public void close() {
            if (target == null) return;
            target.destroyBuffers();
            target = null;
        }
    }
}
