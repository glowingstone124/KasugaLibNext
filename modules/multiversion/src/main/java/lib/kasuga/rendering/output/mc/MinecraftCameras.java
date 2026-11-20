package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.FrameOutputRouter;
import lib.kasuga.rendering.output.OutputFrame;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.CameraProjection;
import lib.kasuga.rendering.output.camera.CameraTarget;
import lib.kasuga.rendering.output.camera.CameraFollowSettings;
import lib.kasuga.rendering.output.camera.CameraSource;
import lib.kasuga.rendering.output.camera.OwnedCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns camera producers and their completed-view subscriptions. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class MinecraftCameras {
    private static final Map<String, DetachedOutput> VIEWS = new LinkedHashMap<>();
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

    /** Creates a detached camera rendering chunks available to the client. */
    public static CameraHandle createFree(String viewId, Supplier<WorldCameraView> pose, CameraRenderSettings settings,
                                          Consumer<OutputFrame<GpuTextureView>> consumer) {
        return createDetached(viewId, new CameraSource.Free(pose), settings, consumer);
    }

    public static CameraHandle createFree(String viewId, WorldCameraView pose, CameraRenderSettings settings,
                                          Consumer<OutputFrame<GpuTextureView>> consumer) {
        Objects.requireNonNull(pose, "pose");
        return createFree(viewId, () -> pose, settings, consumer);
    }

    public static CameraHandle createFree(String viewId, Supplier<WorldCameraView> pose,
                                          Consumer<OutputFrame<GpuTextureView>> consumer) {
        return createFree(viewId, pose, CameraRenderSettings.defaults(), consumer);
    }

    public static CameraHandle createFree(String viewId, WorldCameraView pose, Consumer<OutputFrame<GpuTextureView>> consumer) {
        return createFree(viewId, pose, CameraRenderSettings.defaults(), consumer);
    }

    public static CameraHandle create(String viewId, WorldCameraView pose, CameraRenderSettings settings,
                                      Consumer<OutputFrame<GpuTextureView>> consumer) {
        return createFree(viewId, pose, settings, consumer);
    }

    public static CameraHandle create(String viewId, WorldCameraView pose, Consumer<OutputFrame<GpuTextureView>> consumer) {
        return createFree(viewId, pose, consumer);
    }

    public static CameraHandle create(String viewId, Supplier<WorldCameraView> pose, Consumer<OutputFrame<GpuTextureView>> consumer) {
        return createFree(viewId, pose, consumer);
    }

    public static CameraHandle createFixed(String viewId, CameraTarget target, CameraProjection projection,
                                           CameraFollowSettings follow, CameraRenderSettings settings,
                                           Consumer<OutputFrame<GpuTextureView>> consumer) {
        return createDetached(viewId, new CameraSource.Fixed(target, projection, follow), settings, consumer);
    }

    public static CameraHandle createFixed(String viewId, Entity entity, CameraProjection projection,
                                           CameraRenderSettings settings, Consumer<OutputFrame<GpuTextureView>> consumer) {
        Objects.requireNonNull(entity, "entity");
        return createFixed(viewId, partialTick -> {
            if (entity.isRemoved() || entity.level() != Minecraft.getInstance().level) return null;
            Vec3 eye = entity.getEyePosition(partialTick);
            return new CameraTarget.Pose(eye.x, eye.y, eye.z, entity.getViewYRot(partialTick), entity.getViewXRot(partialTick), 0);
        }, projection, CameraFollowSettings.defaults(), settings, consumer);
    }

    public static CameraHandle create(String viewId, Supplier<WorldCameraView> pose, CameraRenderSettings settings,
                                      Consumer<OutputFrame<GpuTextureView>> consumer) {
        return createFree(viewId, pose, settings, consumer);
    }

    private static CameraHandle createDetached(String viewId, CameraSource source, CameraRenderSettings settings,
                                               Consumer<OutputFrame<GpuTextureView>> consumer) {
        RenderSystem.assertOnRenderThread();
        if (viewId == null || viewId.isBlank() || viewId.equals(MinecraftFrameOutputs.MAIN_VIEW) || VIEWS.containsKey(viewId)) {
            throw new IllegalArgumentException("Invalid or duplicate camera view ID: " + viewId);
        }
        DetachedOutput output = new DetachedOutput(viewId, settings, Objects.requireNonNull(consumer, "consumer"));
        OwnedCamera owner = new OwnedCamera(viewId, source, output, output, RenderSystem::assertOnRenderThread,
                () -> VIEWS.remove(viewId, output));
        output.owner = owner;
        VIEWS.put(viewId, output);
        return owner;
    }

    public static void renderDetachedViews() {
        if (VIEWS.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        for (DetachedOutput output : List.copyOf(VIEWS.values())) {
            try {
                output.render(client);
            } catch (RuntimeException failure) {
                output.owner.fail(failure);
                LogUtils.getLogger().error("Could not render detached camera {}", output.id, failure);
            }
        }
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.isPaused()) return;
        for (DetachedOutput output : List.copyOf(VIEWS.values())) output.owner.tick(1 / 20f);
    }

    @SubscribeEvent public static void chunkLoaded(ChunkEvent.Load event) {
        if (!RenderSystem.isOnRenderThread() || event.getLevel() != Minecraft.getInstance().level || VIEWS.isEmpty()) return;
        markChunkDirty(event.getChunk().getPos());
    }

    public static boolean isCameraLevel(net.minecraft.world.level.LevelAccessor level) { return level instanceof CameraChunkSnapshot; }

    public static void markChunkDirty(ChunkPos position) {
        if (!RenderSystem.isOnRenderThread()) return;
        for (DetachedOutput output : VIEWS.values()) {
            if (output.view != null) output.view.markChunkDirty(position);
        }
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
        for (DetachedOutput output : List.copyOf(VIEWS.values())) closeCamera(output.owner);
        if (player != null) closeCamera(player);
    }

    private static void closeCamera(OwnedCamera owner) {
        try {
            owner.close();
        } catch (Exception failure) {
            LogUtils.getLogger().error("Could not close camera {}", owner.viewId(), failure);
        }
    }

    private static final class DetachedOutput implements OwnedCamera.Producer, OwnedCamera.Output {
        private final String id;
        private final FrameOutputRouter<GpuTextureView>.Registration output;
        private CameraRenderSettings settings;
        private OwnedCamera owner;
        private DetachedWorldCamera view;
        private boolean closed;

        private DetachedOutput(String id, CameraRenderSettings settings, Consumer<OutputFrame<GpuTextureView>> consumer) {
            this.id = id;
            this.settings = supported(settings);
            this.output = MinecraftFrameOutputs.open(id, FrameOutputMode.MIRROR, consumer);
        }

        private void render(Minecraft client) {
            if (view != null && !view.isFor(client.level)) releaseView();
            if (owner.state() != CameraState.READY || client.level == null || client.player == null
                    || !client.gameRenderer.mainCamera().isInitialized()) return;
            WorldCameraView pose = owner.sampleAvailablePose(client.getDeltaTracker().getGameTimeDeltaPartialTick(true)).orElse(null);
            if (pose == null) return;
            if (view == null) view = new DetachedWorldCamera(client, client.level, id, pose, settings);
            view.render(client, pose);
        }

        private void releaseView() {
            if (view == null) return;
            DetachedWorldCamera previous = view;
            view = null;
            previous.close();
        }

        @Override public void setEnabled(boolean enabled) { }
        @Override public boolean isClosed() { return closed || output.isClosed(); }
        @Override public Optional<CameraRenderSettings> renderSettings() { return Optional.of(settings); }

        @Override public void updateRenderSettings(CameraRenderSettings next) {
            supported(next);
            if (next.equals(settings)) return;
            releaseView();
            settings = next;
        }

        private static CameraRenderSettings supported(CameraRenderSettings settings) {
            Objects.requireNonNull(settings, "settings");
            if (settings.shader().pack() != null || !settings.resourcePacks().isEmpty() || settings.mipmapLevels() != 0) {
                throw new UnsupportedOperationException("Detached 26.3 cameras share the host's resource packs, shaders and mipmaps");
            }
            return settings;
        }

        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            try {
                releaseView();
            } finally {
                output.close();
            }
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
