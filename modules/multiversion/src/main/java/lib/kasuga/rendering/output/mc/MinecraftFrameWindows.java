package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.FrameOutputRouter;
import lib.kasuga.rendering.output.FrameWindowOptions;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/** Native windows subscribing to completed views. All operations belong to Minecraft's render thread. */
public final class MinecraftFrameWindows {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<WindowOutput> WINDOWS = new ArrayList<>();

    private MinecraftFrameWindows() {}

    /** Closing this window detaches its subscription to the view. */
    public static WindowOutput open(String viewId, FrameWindowOptions options) {
        RenderSystem.assertOnRenderThread();
        WindowOutput output = new WindowOutput(new RenderPearlPreviewWindow(Minecraft.getInstance(), options));
        try {
            output.source = MinecraftFrameOutputs.open(viewId, FrameOutputMode.MIRROR,
                    frame -> output.display.blit(frame.resource()));
        } catch (RuntimeException failure) {
            output.display.close();
            throw failure;
        }
        WINDOWS.add(output);
        return output;
    }

    /** Called immediately after the host submits its frame commands. */
    public static void presentSubmittedFrames() {
        for (int index = WINDOWS.size() - 1; index >= 0; index--) {
            WindowOutput output = WINDOWS.get(index);
            output.display.presentSubmittedFrame();
            if (output.display.closeRequested() || output.source.isClosed()) closeWindow(output);
        }
    }

    public static void shutdown() {
        for (WindowOutput output : List.copyOf(WINDOWS)) {
            closeWindow(output);
        }
        if (WINDOWS.isEmpty()) return;
        // If shutdown interrupted a frame after a preview blit, drain its recorded commands once.
        RenderSystem.getDevice().createCommandEncoder().submit();
        presentSubmittedFrames();
    }

    private static void closeWindow(WindowOutput output) {
        try {
            output.close();
        } catch (Exception failure) {
            LOGGER.error("Could not close a frame output window", failure);
        }
    }

    public static final class WindowOutput implements AutoCloseable {
        private final RenderPearlPreviewWindow display;
        private FrameOutputRouter<GpuTextureView>.Registration source;

        private WindowOutput(RenderPearlPreviewWindow display) {
            this.display = display;
        }

        public long windowHandle() { RenderSystem.assertOnRenderThread(); return display.handle(); }
        public int[] framebufferSize() { RenderSystem.assertOnRenderThread(); return display.framebufferSize(); }
        public long presentedFrames() { RenderSystem.assertOnRenderThread(); return display.presentations(); }
        public boolean isClosed() { return windowHandle() == 0; }
        public void resize(int width, int height) { RenderSystem.assertOnRenderThread(); display.resize(width, height); }
        public void requestClose() { RenderSystem.assertOnRenderThread(); display.requestClose(); }

        @Override
        public void close() throws Exception {
            RenderSystem.assertOnRenderThread();
            display.requestClose();
            // This image belongs to the submission in progress. Finish presenting before destroying the surface.
            if (display.isAcquired() || !WINDOWS.remove(this)) return;
            try {
                source.close();
            } finally {
                display.close();
            }
        }
    }
}
