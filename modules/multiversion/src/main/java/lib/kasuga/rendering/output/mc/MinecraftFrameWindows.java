package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.FrameOutputRouter;
import lib.kasuga.rendering.output.FrameWindowOptions;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraAnimationPlayer;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.camera.CameraType;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

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

    /** Owns a free camera whose output dimensions follow the window's framebuffer. */
    public static WindowCamera createCamera(String viewId, Supplier<WorldCameraView> pose,
                                            CameraRenderSettings settings, FrameWindowOptions options) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(pose, "pose");
        WindowCamera window = new WindowCamera(new RenderPearlPreviewWindow(Minecraft.getInstance(), options));
        try {
            window.camera = MinecraftCameras.createFree(viewId, () -> window.windowPose(pose.get()), settings,
                    frame -> window.preview().blit(frame.resource()));
            WINDOWS.add(window);
        } catch (RuntimeException failure) {
            window.preview().close();
            throw failure;
        }
        return window;
    }

    /** Called immediately after the host submits its frame commands. */
    public static void presentSubmittedFrames() {
        for (int index = WINDOWS.size() - 1; index >= 0; index--) {
            WindowOutput output = WINDOWS.get(index);
            output.display.presentSubmittedFrame();
            if (output.display.closeRequested() || output.sourceClosed()) closeWindow(output);
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

    public static sealed class WindowOutput implements AutoCloseable permits WindowCamera {
        private final RenderPearlPreviewWindow display;
        private FrameOutputRouter<GpuTextureView>.Registration source;

        private WindowOutput(RenderPearlPreviewWindow display) {
            this.display = display;
        }

        final RenderPearlPreviewWindow preview() { return display; }
        boolean sourceClosed() { return source.isClosed(); }
        void closeSource() throws Exception { source.close(); }

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
                closeSource();
            } finally {
                display.close();
            }
        }
    }

    public static final class WindowCamera extends WindowOutput implements CameraHandle {
        private CameraHandle camera;
        private int lastWidth;
        private int lastHeight;

        private WindowCamera(RenderPearlPreviewWindow display) { super(display); }

        private WorldCameraView windowPose(WorldCameraView pose) {
            int[] size = framebufferSize();
            if (size[0] > 0 && size[1] > 0) {
                lastWidth = size[0];
                lastHeight = size[1];
            }
            return new WorldCameraView(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch(), pose.roll(), pose.verticalFov(),
                    lastWidth > 0 ? lastWidth : pose.width(), lastHeight > 0 ? lastHeight : pose.height());
        }

        @Override boolean sourceClosed() { return camera.state() == CameraState.CLOSED || camera.state() == CameraState.FAILED; }
        @Override void closeSource() throws Exception { camera.close(); }
        @Override public String viewId() { return camera.viewId(); }
        @Override public CameraType type() { return camera.type(); }
        @Override public CameraState state() { return camera.state(); }
        @Override public Optional<Throwable> failure() { return camera.failure(); }
        @Override public WorldCameraView pose() { return windowPose(camera.pose()); }
        @Override public CameraAnimationPlayer animation() { return camera.animation(); }
        @Override public Optional<CameraRenderSettings> renderSettings() { return camera.renderSettings(); }
        @Override public void updateRenderSettings(CameraRenderSettings settings) { camera.updateRenderSettings(settings); }
        @Override public void updatePose(Supplier<WorldCameraView> pose) {
            Objects.requireNonNull(pose, "pose");
            camera.updatePose(() -> windowPose(pose.get()));
        }
        @Override public void pause() { camera.pause(); }
        @Override public void resume() { camera.resume(); }
    }
}
