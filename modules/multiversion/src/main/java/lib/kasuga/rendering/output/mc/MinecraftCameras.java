package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.FrameOutputRouter;
import lib.kasuga.rendering.output.OutputFrame;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraSource;
import lib.kasuga.rendering.output.camera.OwnedCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.function.Consumer;

/** Owns camera producers and their completed-view subscriptions. */
public final class MinecraftCameras {
    private static OwnedCamera player;

    private MinecraftCameras() {}

    /** Subscribes to the final player frame, including the world, hands, post-processing and GUI. */
    public static CameraHandle createPlayer(Consumer<OutputFrame<GpuTextureView>> consumer) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(consumer, "consumer");
        if (player != null) throw new IllegalArgumentException("Duplicate camera: " + MinecraftFrameOutputs.MAIN_VIEW);
        PlayerOutput output = new PlayerOutput(consumer);
        OwnedCamera camera = new OwnedCamera(MinecraftFrameOutputs.MAIN_VIEW, new CameraSource.Player(MinecraftCameras::playerPose),
                output, output, RenderSystem::assertOnRenderThread, () -> player = null);
        output.owner = camera;
        player = camera;
        return camera;
    }

    private static WorldCameraView playerPose(float partialTick) {
        Minecraft client = Minecraft.getInstance();
        Camera nativeCamera = client.gameRenderer.mainCamera();
        Vec3 position = nativeCamera.position();
        float fov = nativeCamera.isInitialized() ? nativeCamera.getFov() : client.options.fov().get();
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        return new WorldCameraView(position.x, position.y, position.z, nativeCamera.yRot(), nativeCamera.xRot(), nativeCamera.getRoll(),
                Math.clamp(fov, Math.nextUp(0f), Math.nextDown(180f)), Math.max(1, target.width), Math.max(1, target.height));
    }

    public static void shutdown() {
        RenderSystem.assertOnRenderThread();
        if (player == null) return;
        try {
            player.close();
        } catch (Exception failure) {
            LogUtils.getLogger().error("Could not close player camera output", failure);
        }
    }

    private static final class PlayerOutput implements OwnedCamera.Producer, OwnedCamera.Output {
        private final Consumer<OutputFrame<GpuTextureView>> consumer;
        private FrameOutputRouter<GpuTextureView>.Registration subscription;
        private OwnedCamera owner;
        private boolean closed;

        private PlayerOutput(Consumer<OutputFrame<GpuTextureView>> consumer) {
            this.consumer = consumer;
            setEnabled(true);
        }

        @Override
        public void setEnabled(boolean enabled) {
            if (closed) throw new IllegalStateException("Player output closed");
            if (enabled && subscription == null) {
                subscription = MinecraftFrameOutputs.open(FrameOutputMode.MIRROR, this::deliver);
            } else if (!enabled) {
                try {
                    detach();
                } catch (Exception failure) {
                    throw new IllegalStateException("Could not pause the player output", failure);
                }
            }
        }

        private void deliver(OutputFrame<GpuTextureView> frame) {
            try {
                consumer.accept(frame);
            } catch (RuntimeException failure) {
                owner.fail(failure);
                throw failure;
            }
        }

        @Override
        public boolean isClosed() {
            return closed || (subscription != null && subscription.isClosed());
        }

        @Override
        public void close() throws Exception {
            if (closed) return;
            closed = true;
            detach();
        }

        private void detach() throws Exception {
            if (subscription == null) return;
            FrameOutputRouter<GpuTextureView>.Registration previous = subscription;
            subscription = null;
            previous.close();
        }
    }
}
