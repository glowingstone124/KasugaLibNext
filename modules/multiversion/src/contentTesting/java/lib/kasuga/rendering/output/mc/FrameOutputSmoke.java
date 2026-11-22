package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.rendering.effect.post.PostProcessContext;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.FrameOutputRouter;
import lib.kasuga.rendering.output.OutputFrame;
import lib.kasuga.rendering.output.FrameWindowOptions;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import net.minecraft.world.phys.Vec3;
import lib.kasuga.rendering.output.mc.stream.RemoteCameraSmoke;
import lib.kasuga.rendering.output.camera.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Vector4f;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.system.MemoryStack;

/** Exercises real presentation and readback after the other screenshot tests finish. */
public final class FrameOutputSmoke {
    private static final int GUI_COLOR = 0xffb42c65;
    private static Screen screen;
    private static FrameOutputRouter<GpuTextureView>.Registration mirror;
    private static FrameOutputRouter<GpuTextureView>.Registration exclusive;
    private static OutputFrame<GpuTextureView> current;
    private static MinecraftFrameWindows.WindowOutput preview;
    private static MinecraftFrameWindows.WindowOutput secondPreview;
    private static int[] initialSize;
    private static long beforeResize;
    private static boolean closeSent;
    private static int closedWindowId;
    private static boolean sampled;
    private static boolean fitted;
    private static boolean hdrCopied;
    private static boolean finished;
    private static RuntimeException failure;
    private static int frames;
    private static int mirrorFrames;
    private static CameraHandle playerCamera;
    private static int playerFrames;
    private static boolean playerResumed;
    private static CameraHandle[] detached;
    private static final int[] cameraFrames = new int[2];
    private static final int[] cameraPixels = new int[2];
    private static WorldCameraView downward;
    private static boolean detachedPassed;
    private static boolean detachedMoved;
    private static int movedPixel;
    private static long ownedWindowFrames;
    private static MinecraftFrameWindows.WindowCamera ownedWindow;
    private static int exclusiveFrames;

    private FrameOutputSmoke() {}

    public static void start(Minecraft client) {
        screen = new Screen(Component.literal("Frame output smoke")) {
            @Override public boolean isPauseScreen() { return false; }

            @Override
            public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
                graphics.fill(0, 0, width, height, GUI_COLOR);
            }
        };
        client.gui.setScreen(screen);
    }

    public static boolean started() { return screen != null; }

    public static void afterFrame(Minecraft client) throws Exception {
        if (finished) return;
        if (failure != null) throw failure;
        if (!RemoteCameraSmoke.verify(client)) return;
        if (!DetachedCameraIsolationSmoke.verify(client)) return;
        if (++frames > 240) throw new IllegalStateException("Frame output smoke expired without a readback");
        if (frames < 3 || !verifyDetachedViews(client)) return;
        if (mirror == null) {
            verifyCustomView();
            verifyFittedCopy();
            preview = MinecraftFrameWindows.open(MinecraftFrameOutputs.MAIN_VIEW,
                    new FrameWindowOptions("Kasuga Frame Output Smoke", 320, 180));
            secondPreview = MinecraftFrameWindows.open(MinecraftFrameOutputs.MAIN_VIEW,
                    new FrameWindowOptions("Kasuga Second Frame Output Smoke", 300, 160));
            mirror = MinecraftFrameOutputs.open(FrameOutputMode.MIRROR, frame -> {
                current = frame;
                if (++mirrorFrames == 1) readGuiPixel(frame);
            });
            playerCamera = MinecraftCameras.createPlayer(frame -> {
                try {
                    require(frame == current, "Player did not share the completed main frame");
                    playerFrames++;
                } catch (RuntimeException error) {
                    failure = error;
                }
            });
            exclusive = MinecraftFrameOutputs.open(FrameOutputMode.OFFSCREEN_ONLY, frame -> {
                try {
                    require(frame == current && mirror.latest().orElseThrow() == frame,
                            "Subscribers did not share exactly one snapshot");
                    require(client.windowSurface().isAcquired(), "No surface to present the exclusive frame");
                    exclusiveFrames++;
                } catch (RuntimeException error) {
                    failure = error;
                }
            });
            return;
        }
        require(!mirror.isClosed() && !exclusive.isClosed(), "Frame router detached a smoke subscriber");
        verifyPlayerCamera(client);
        if (!sampled || !fitted || !hdrCopied || exclusiveFrames < 3 || !verifyPreviewWindows(client)) return;
        require(playerCamera.state() == CameraState.CLOSED, "Player camera did not finish the lifecycle probe");
        exclusive.close();
        require(!current.resource().isClosed(), "Closing one consumer retired a shared snapshot");
        mirror.close();
        require(current.resource().isClosed() && !MinecraftFrameOutputs.hasOutputs(MinecraftFrameOutputs.MAIN_VIEW),
                "Last subscription did not retire the output snapshot");
        // This frame presents the GUI again, and the client shuts down on the following tick.
        LogUtils.getLogger().info("KASUGA_FRAME_OUTPUT_PASS final-gui,shared,exclusive,retired,custom-view,resize,hdr,format-switch");
        finished = true;
        client.stop();
    }

    private static boolean verifyDetachedViews(Minecraft client) throws Exception {
        if (detachedPassed) return true;
        if (detached == null) {
            Vec3 eye = client.gameRenderer.mainCamera().position();
            downward = new WorldCameraView(eye.x, eye.y, eye.z, 0, 80, 0, 60, 128, 96);
            WorldCameraView upward = downward.withRotation(0, -75, 0);
            CameraRenderSettings settings = new CameraRenderSettings(3, CameraRenderSettings.Quality.FAST,
                    CameraRenderSettings.Shader.disabled());
            ownedWindow = MinecraftFrameWindows.createCamera("kasuga:owned-camera", () -> downward, settings,
                    new FrameWindowOptions("Kasuga Independent Camera", 160, 96));
            detached = new CameraHandle[]{
                    MinecraftCameras.createFree("kasuga:down", () -> downward, settings, frame -> recordCamera(frame, 0)),
                    MinecraftCameras.createFree("kasuga:up", upward, settings, frame -> recordCamera(frame, 1))
            };
            return false;
        }
        for (CameraHandle camera : detached) {
            if (camera.state() == CameraState.FAILED) throw new IllegalStateException("Detached camera failed", camera.failure().orElse(null));
        }
        if (cameraPixels[0] == 0 || cameraPixels[1] == 0) return false;
        require(cameraPixels[0] != cameraPixels[1], "Detached cameras rendered identical center pixels: " + Integer.toHexString(cameraPixels[0]));
        if (!detachedMoved) {
            require(ownedWindow.presentedFrames() > 0, "Detached camera window did not present");
            detachedMoved = true;
            // Match the other camera's orientation plus change output size.
            downward = new WorldCameraView(downward.x(), downward.y(), downward.z(), 0, -75, 0, 60, 160, 120);
            ownedWindowFrames = ownedWindow.presentedFrames();
            ownedWindow.resize(200, 120);
            return false;
        }
        if (movedPixel == 0 || ownedWindow.presentedFrames() < ownedWindowFrames + 3) return false;
        require(movedPixel != cameraPixels[0], "Updating the camera pose did not change the output");
        int[] framebufferSize = ownedWindow.framebufferSize();
        require(ownedWindow.pose().width() == framebufferSize[0] && ownedWindow.pose().height() == framebufferSize[1],
                "Window-owned camera did not adopt the new framebuffer dimensions");
        ownedWindow.close();
        require(ownedWindow.isClosed() && !MinecraftFrameOutputs.hasOutputs("kasuga:owned-camera"), "Owned window leaked the camera");
        for (CameraHandle camera : detached) camera.close();
        require(!MinecraftFrameOutputs.hasOutputs("kasuga:down") && !MinecraftFrameOutputs.hasOutputs("kasuga:up"),
                "Detached outputs leaked subscriptions");
        detachedPassed = true;
        LogUtils.getLogger().info("KASUGA_DETACHED_CAMERA_PASS distinct-pixels,move,resize,owned-window,close {} {} {}", Integer.toHexString(cameraPixels[0]), Integer.toHexString(cameraPixels[1]), Integer.toHexString(movedPixel));
        return true;
    }

    private static void recordCamera(OutputFrame<GpuTextureView> frame, int index) {
        boolean moved = index == 0 && detachedMoved && movedPixel == 0;
        if (++cameraFrames[index] != 45 && !moved) return;
        if (moved) require(frame.width() == 160 && frame.height() == 120, "Detached output did not resize");
        TextureTarget screenshot = new TextureTarget("Detached camera smoke", frame.width(), frame.height(), GpuFormat.RGBA8_UNORM, null);
        try {
            PostProcessContext.copyColor(frame.resource(), screenshot.getColorTextureView(), false);
            Screenshot.takeScreenshot(screenshot, pixels -> {
                try (pixels) {
                    int center = pixels.getPixel(frame.width() / 2, frame.height() / 2);
                    if (moved) movedPixel = center;
                    else cameraPixels[index] = center;
                }
            });
        } finally {
            screenshot.destroyBuffers();
        }
    }

    private static void verifyPlayerCamera(Minecraft client) throws Exception {
        if (mirrorFrames == 1) {
            require(playerFrames == 1 && playerCamera.type() == CameraType.PLAYER, "Missing player output");
            WorldCameraView pose = playerCamera.pose();
            require(pose.width() == client.gameRenderer.mainRenderTarget().width
                            && pose.height() == client.gameRenderer.mainRenderTarget().height
                            && pose.yaw() == client.gameRenderer.mainCamera().yRot(), "Player pose did not match the live main view");
            playerCamera.pause();
        } else if (mirrorFrames == 3) {
            require(playerFrames == 1 && playerCamera.state() == CameraState.PAUSED && !playerResumed,
                    "Paused player camera unexpectedly received a frame");
            playerCamera.resume();
            playerResumed = true;
        } else if (mirrorFrames == 5) {
            require(playerFrames == 3 && playerCamera.state() == CameraState.READY, "Player camera did not resume");
            playerCamera.close();
            try (CameraHandle replacement = MinecraftCameras.createPlayer(frame -> {})) {
                require(replacement.state() == CameraState.READY, "Player owner did not release its reservation");
            }
            LogUtils.getLogger().info("KASUGA_PLAYER_CAMERA_PASS host-pose,shared-frame,pause,resume,close,reopen");
        }
    }

    private static boolean verifyPreviewWindows(Minecraft client) throws Exception {
        if (beforeResize == 0) {
            if (preview.presentedFrames() < 3 || secondPreview.presentedFrames() < 3) return false;
            require(preview.windowHandle() != client.getWindow().handle()
                            && preview.windowHandle() != secondPreview.windowHandle(), "Expected distinct SDL windows");
            if ((SDLVideo.SDL_GetWindowFlags(client.getWindow().handle()) & SDLVideo.SDL_WINDOW_OPENGL) != 0) {
                require(SDLVideo.SDL_GL_GetCurrentWindow() == client.getWindow().handle(), "GL drawable was not restored");
            }
            initialSize = preview.framebufferSize();
            beforeResize = preview.presentedFrames();
            preview.resize(480, 270);
            return false;
        }
        if (!closeSent) {
            int[] resized = preview.framebufferSize();
            if (resized[0] <= initialSize[0] || resized[1] <= initialSize[1] || preview.presentedFrames() < beforeResize + 3) {
                return false;
            }
            closedWindowId = SDLVideo.SDL_GetWindowID(preview.windowHandle());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                SDL_Event event = SDL_Event.calloc(stack);
                event.type(SDLEvents.SDL_EVENT_WINDOW_CLOSE_REQUESTED);
                event.window().windowID(closedWindowId);
                require(SDLEvents.SDL_PushEvent(event), "Could not push SDL close request");
            }
            closeSent = true;
            return false;
        }
        if (!preview.isClosed()) return false;
        require(SDLVideo.SDL_GetWindowFromID(closedWindowId) == 0, "Closing did not destroy the native window");
        require(!secondPreview.isClosed() && secondPreview.presentedFrames() > beforeResize,
                "Closing one preview interrupted the other");
        secondPreview.close();
        require(secondPreview.isClosed(), "Explicit close did not release the second window");
        LogUtils.getLogger().info("KASUGA_FRAME_WINDOW_PASS backend={} two-surfaces,aspect-fit-pixels,resize,restored,close-event,explicit-close",
                RenderSystem.getDevice().getDeviceInfo().backendName());
        return true;
    }

    private static void verifyFittedCopy() {
        TextureTarget source = new TextureTarget("Preview fit source", 24, 12, GpuFormat.RGBA8_UNORM, null);
        TextureTarget fitted = new TextureTarget("Preview fit destination", 48, 48, GpuFormat.RGBA8_UNORM, null);
        try {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(source.getColorTexture(),
                    new Vector4f(1, 0, 1, 1));
            PostProcessContext.fitColor(source.getColorTextureView(), fitted.getColorTextureView());
            Screenshot.takeScreenshot(fitted, pixels -> {
                try (pixels) {
                    require(pixels.getPixel(1, 1) == 0xff000000 && pixels.getPixel(46, 46) == 0xff000000,
                            "Preview fit did not clear the letterbox bars");
                    require(pixels.getPixel(1, 12) == 0xffff00ff && pixels.getPixel(46, 35) == 0xffff00ff,
                            "Preview fit did not preserve the source aspect ratio");
                    FrameOutputSmoke.fitted = true;
                } catch (RuntimeException error) {
                    failure = error;
                }
            });
        } finally {
            fitted.destroyBuffers();
            source.destroyBuffers();
        }
    }

    private static void verifyCustomView() throws Exception {
        String id = "kasuga:smoke";
        TextureTarget source = new TextureTarget("Frame output test source", 8, 10, GpuFormat.RGBA8_UNORM, null);
        TextureTarget halfFloat = new TextureTarget("Frame output half float", 12, 14, GpuFormat.RGBA16_FLOAT, null);
        TextureTarget fullFloat = new TextureTarget("Frame output full float", 12, 14, GpuFormat.RGBA32_FLOAT, null);
        try (FrameOutputRouter<GpuTextureView>.Registration custom = MinecraftFrameOutputs.open(id, FrameOutputMode.MIRROR, frame -> {})) {
            clear(source);
            require(MinecraftFrameOutputs.publish(id, source).delivered() == 1, "Custom view was not delivered");
            OutputFrame<GpuTextureView> first = custom.latest().orElseThrow();
            source.resize(12, 14);
            clear(source);
            require(MinecraftFrameOutputs.publish(id, source).delivered() == 1, "Resized view was not delivered");
            OutputFrame<GpuTextureView> resized = custom.latest().orElseThrow();
            require(resized.width() == 12 && resized.height() == 14 && resized.frameNumber() == first.frameNumber() + 1
                            && resized.resource() != first.resource() && first.resource().isClosed(),
                    "Resizing did not retire the old custom view snapshot");
            clear(halfFloat);
            require(MinecraftFrameOutputs.publish(id, halfFloat).delivered() == 1, "Half-float view was not delivered");
            GpuTextureView halfSnapshot = custom.latest().orElseThrow().resource();
            require(resized.resource().isClosed() && halfSnapshot.texture().getFormat() == GpuFormat.RGBA16_FLOAT,
                    "The same-size format switch did not replace the previous snapshot");
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(fullFloat.getColorTexture(),
                    new Vector4f(2.5f, .25f, .75f, 1f));
            require(MinecraftFrameOutputs.publish(id, fullFloat).delivered() == 1, "Full-float view was not delivered");
            GpuTextureView hdr = custom.latest().orElseThrow().resource();
            require(halfSnapshot.isClosed() && hdr.texture().getFormat() == GpuFormat.RGBA32_FLOAT,
                    "The frame output did not preserve full-float storage");
            readHdrPixel(hdr);
        } finally {
            source.destroyBuffers();
            halfFloat.destroyBuffers();
            fullFloat.destroyBuffers();
        }
    }

    private static void readHdrPixel(GpuTextureView source) {
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Frame output HDR pixel",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 16);
        try {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(source.texture(), buffer, 0, () -> {
                try (buffer; GpuBufferSlice.MappedView mapped = buffer.map(true, false)) {
                    require(mapped.data().getFloat(0) == 2.5f && mapped.data().getFloat(4) == .25f
                                    && mapped.data().getFloat(8) == .75f && mapped.data().getFloat(12) == 1f,
                            "The HDR snapshot changed the source pixel");
                    hdrCopied = true;
                } catch (RuntimeException error) {
                    failure = error;
                }
            }, 0, 0, 0, 1, 1);
        } catch (RuntimeException error) {
            buffer.close();
            throw error;
        }
    }

    private static void clear(TextureTarget target) {
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.getColorTexture(), new Vector4f(0, 0, 0, 1));
    }

    private static void readGuiPixel(OutputFrame<GpuTextureView> frame) {
        GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Frame output smoke pixel",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 4);
        try {
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(frame.resource().texture(), buffer, 0,
                    () -> {
                        try (buffer; GpuBufferSlice.MappedView mapped = buffer.map(true, false)) {
                            int red = mapped.data().get(0) & 255;
                            int green = mapped.data().get(1) & 255;
                            int blue = mapped.data().get(2) & 255;
                            require(Math.abs(red - 180) < 3 && Math.abs(green - 44) < 3 && Math.abs(blue - 101) < 3,
                                    "Expected final GUI color, got RGB " + red + "," + green + "," + blue);
                            sampled = true;
                        } catch (RuntimeException error) {
                            failure = error;
                        }
                    }, 0, frame.width() / 2, frame.height() / 2, 1, 1);
        } catch (RuntimeException error) {
            buffer.close();
            throw error;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
