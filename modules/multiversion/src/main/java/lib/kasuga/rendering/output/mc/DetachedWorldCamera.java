package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import lib.kasuga.core.rendering.mixin.CameraViewAccess;
import lib.kasuga.rendering.effect.pipeline.WorldProjection;
import lib.kasuga.rendering.effect.builtin.cloud.CloudEffects;
import net.minecraft.gizmos.Gizmos;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;

/** An independently extracted and rendered vanilla view in the current client world. */
final class DetachedWorldCamera implements AutoCloseable {
    private final CameraRenderSettings settings;
    private final CameraRenderScope scope;
    private final CameraChunkSnapshot world;
    private final TextureTarget target;
    private final LevelExtractor extractor;
    private final LevelRenderer renderer;
    private final FogRenderer fog = new FogRenderer();
    private final ProjectionMatrixBuffer projection = new ProjectionMatrixBuffer("Kasuga detached camera");
    private final GlobalSettingsUniform globals = new GlobalSettingsUniform();
    private final CrossFrameResourcePool pool = new CrossFrameResourcePool(3);

    DetachedWorldCamera(Minecraft client, ClientLevel source, String viewId, WorldCameraView pose, CameraRenderSettings settings) {
        this.settings = settings;
        target = new TextureTarget("Kasuga camera " + viewId, pose.width(), pose.height(), GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        GameRenderState state = new GameRenderState();
        state.optionsRenderState.renderDistance = settings.renderDistance();
        RenderBuffers buffers = new RenderBuffers(1);
        FeatureRenderDispatcher features = new FeatureRenderDispatcher(buffers, client.getModelManager(), client.getAtlasManager(), client.font, state);
        scope = new CameraRenderScope(viewId, new PoseCamera(), target, buffers, features, state);
        renderer = scope.call(() -> new LevelRenderer(client.getEntityRenderDispatcher(), client.getBlockEntityRenderDispatcher(),
                client.getModelManager(), client.getTextureManager(), client.getAtlasManager(), client.getShaderManager(),
                client.gameRenderer, pose.width(), pose.height()));
        extractor = new LevelExtractor(client, state.levelRenderState, renderer);
        world = new CameraChunkSnapshot(client, source, extractor, settings.renderDistance());
        scope.run(() -> extractor.setLevel(world));
        scope.camera().setLevel(world);
    }

    void render(Minecraft client, WorldCameraView pose) {
        if (target.width != pose.width() || target.height != pose.height()) {
            pool.clear();
            target.resize(pose.width(), pose.height());
            renderer.resize(pose.width(), pose.height());
        }
        Camera host = client.gameRenderer.mainCamera();
        configureOptions(client.gameRenderer.gameRenderState().optionsRenderState);
        CameraRenderState state = scope.state().levelRenderState.cameraRenderState;
        DeltaTracker ticks = client.getDeltaTracker();
        float partialTick = ticks.getGameTimeDeltaPartialTick(false);
        PoseCamera camera = (PoseCamera) scope.camera();
        camera.moveTo(pose, settings.renderDistance());
        world.update(camera.position());
        camera.setEntity(host.entity());
        camera.attributeProbe().tick(world, camera.position());
        camera.extractRenderState(state, ticks);
        state.isFirstPerson = false;
        state.fogType = camera.getFluidInCamera();
        state.fogData = fog.setupFog(camera, settings.renderDistance(), ticks, 0, world);
        scope.state().windowRenderState.width = pose.width();
        scope.state().windowRenderState.height = pose.height();
        scope.state().windowRenderState.appropriateLineWidth = Math.max(2.5f, pose.width() / 1920f * 2.5f);
        GpuBufferSlice savedProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType savedProjectionType = RenderSystem.getProjectionType();
        GpuBuffer savedGlobals = RenderSystem.getGlobalSettingsUniform();
        Matrix4f savedWorldProjection = new Matrix4f(WorldProjection.current());
        try {
            scope.run(() -> {
                try (Gizmos.TemporaryCollection ignored = extractor.collectPerFrameMainThreadGizmos()) {
                    extractor.extract(ticks, camera, partialTick);
                }
                scope.state().levelRenderState.playerCompiledSectionCallback = null;
                scope.state().levelRenderState.render3dCrosshair = false;
                RenderSystem.setProjectionMatrix(projection.getBuffer(state.projectionMatrix), ProjectionType.PERSPECTIVE);
                WorldProjection.capture(state.projectionMatrix);
                globals.update(pose.width(), pose.height(), scope.state().optionsRenderState.glintStrength,
                        world.getGameTime(), partialTick, 0, camera.position(), false);
                fog.updateBuffer(state.fogData);
                try (Gizmos.TemporaryCollection ignored = renderer.collectPerFrameRenderThreadGizmos()) {
                    renderer.render(pool, false, state, fog.getBuffer(FogRenderer.FogMode.WORLD), state.fogData.color, true, false);
                }
                NeoForge.EVENT_BUS.post(new RenderLevelStageEvent.AfterLevel(renderer, scope.state().levelRenderState,
                        null, state.viewRotationMatrix, renderer.visibleSections()));
                renderer.blitEntityOutline();
                renderer.endFrame();
                fog.endFrame();
                scope.buffers().endFrame();
                pool.endFrame();
            });
        } finally {
            RenderSystem.setProjectionMatrix(savedProjection, savedProjectionType);
            RenderSystem.setGlobalSettingsUniform(savedGlobals);
            WorldProjection.capture(savedWorldProjection);
            client.getEntityRenderDispatcher().prepare(host, client.crosshairPickEntity);
            client.getBlockEntityRenderDispatcher().prepare(host.position());
        }
        MinecraftFrameOutputs.publish(scope.viewId(), target);
    }

    void tickStreaming() { world.tickStreaming(); }

    void markChunkDirty(net.minecraft.world.level.ChunkPos position) { world.markDirty(position); }

    boolean isFor(ClientLevel level) { return world.isFor(level); }

    private void configureOptions(OptionsRenderState host) {
        OptionsRenderState options = scope.state().optionsRenderState;
        options.ambientOcclusion = settings.ambientOcclusion();
        options.cloudRange = host.cloudRange;
        options.cloudStatus = host.cloudStatus;
        options.cutoutLeaves = host.cutoutLeaves;
        options.improvedTransparency = settings.quality() == CameraRenderSettings.Quality.FANCY && host.improvedTransparency;
        options.maxAnisotropyValue = host.maxAnisotropyValue;
        options.textureFiltering = host.textureFiltering;
        options.glintStrength = host.glintStrength;
        options.glintSpeed = host.glintSpeed;
        options.chunkSectionFadeInTime = host.chunkSectionFadeInTime;
        options.prioritizeChunkUpdates = host.prioritizeChunkUpdates;
    }

    @Override public void close() {
        CloudEffects.releaseView(scope.viewId());
        renderer.close();
        world.close();
        scope.features().close();
        scope.buffers().fixedBufferPack().close();
        scope.buffers().close();
        target.destroyBuffers();
        fog.close();
        projection.close();
        globals.close();
        pool.close();
    }

    private static final class PoseCamera extends Camera {
        void moveTo(WorldCameraView pose, int renderDistance) {
            setPosition(pose.x(), pose.y(), pose.z());
            setRotation(pose.yaw(), pose.pitch(), pose.roll());
            float far = renderDistance * 64f;
            CameraViewAccess access = (CameraViewAccess) (Camera) this;
            access.kasuga$setInitialized(true);
            access.kasuga$setDetached(true);
            access.kasuga$setDepthFar(far);
            access.kasuga$setFov(pose.verticalFov());
            access.kasuga$setPerspective(.05f, far, pose.verticalFov(), pose.width(), pose.height());
            boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
            Matrix4f cullProjection = new Matrix4f().setPerspective((float) Math.toRadians(pose.verticalFov()),
                    pose.aspectRatio(), .05f, far, zeroToOne);
            access.kasuga$prepareFrustum(getViewRotationMatrix(new Matrix4f()), cullProjection, new Vec3(pose.x(), pose.y(), pose.z()));
        }
    }
}
