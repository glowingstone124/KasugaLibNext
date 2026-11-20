package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.FrameOutputRouter;
import lib.kasuga.rendering.output.OutputFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Vector4f;

/** Exercises real presentation and readback after the other screenshot tests finish. */
public final class FrameOutputSmoke {
    private static final int GUI_COLOR = 0xffb42c65;
    private static Screen screen;
    private static FrameOutputRouter<GpuTextureView>.Registration mirror;
    private static FrameOutputRouter<GpuTextureView>.Registration exclusive;
    private static OutputFrame<GpuTextureView> current;
    private static boolean sampled;
    private static boolean finished;
    private static RuntimeException failure;
    private static int frames;
    private static int mirrorFrames;
    private static int exclusiveFrames;

    private FrameOutputSmoke() {}

    public static void start(Minecraft client) {
        screen = new Screen(Component.literal("Frame output smoke")) {
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
        if (++frames > 240) throw new IllegalStateException("Frame output smoke expired without a readback");
        if (frames < 3) return;
        if (mirror == null) {
            verifyCustomView();
            mirror = MinecraftFrameOutputs.open(FrameOutputMode.MIRROR, frame -> {
                current = frame;
                if (++mirrorFrames == 1) readGuiPixel(frame);
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
        if (!sampled || exclusiveFrames < 3) return;
        exclusive.close();
        require(!current.resource().isClosed(), "Closing one consumer retired a shared snapshot");
        mirror.close();
        require(current.resource().isClosed() && !MinecraftFrameOutputs.hasOutputs(MinecraftFrameOutputs.MAIN_VIEW),
                "Last subscription did not retire the output snapshot");
        // This frame presents the GUI again, and the client shuts down on the following tick.
        LogUtils.getLogger().info("KASUGA_FRAME_OUTPUT_PASS final-gui,shared,exclusive,retired,custom-view,resize");
        finished = true;
        client.stop();
    }

    private static void verifyCustomView() throws Exception {
        String id = "kasuga:smoke";
        TextureTarget source = new TextureTarget("Frame output test source", 8, 10, GpuFormat.RGBA8_UNORM, null);
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
        } finally {
            source.destroyBuffers();
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
