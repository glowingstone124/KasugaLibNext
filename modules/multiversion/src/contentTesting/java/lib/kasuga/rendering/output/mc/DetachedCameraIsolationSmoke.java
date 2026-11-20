package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.cloud.SkyCloudLayer;
import lib.kasuga.rendering.cloud.SkyCloudRendering;
import lib.kasuga.rendering.cloud.SkyCloudSettings;
import lib.kasuga.rendering.effect.builtin.cloud.CloudEffects;
import lib.kasuga.rendering.effect.post.PostProcessContext;
import lib.kasuga.rendering.output.OutputFrame;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraProjection;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.CameraState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;

import java.util.List;

/** Probes two different framebuffer sizes, the Fixed entity binding, and isolated history. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class DetachedCameraIsolationSmoke {
    private static final String FIXED = "kasuga:fixed-history";
    private static final String FREE = "kasuga:free-history";
    private static final CameraProjection PROJECTION = new CameraProjection(60, 144, 80);
    private static final HistoryProbe FIXED_HISTORY = new HistoryProbe(144, 80);
    private static final HistoryProbe FREE_HISTORY = new HistoryProbe(96, 72);

    private enum Step { INITIAL, MOVED, ONE_CLOSED, DONE }
    private static Step step = Step.INITIAL;
    private static CameraHandle fixed;
    private static CameraHandle free;
    private static Camera host;
    private static ArmorStand target;
    private static Vec3 hostPosition;
    private static Vec3 fixedPosition;
    private static Vec3 freePosition;
    private static float fixedYaw;
    private static SkyCloudSettings savedSky;
    private static boolean savedEnabled;
    private static boolean savedSkyEnabled;
    private static long deadline;

    private DetachedCameraIsolationSmoke() {}

    static boolean verify(Minecraft client) throws Exception {
        if (step == Step.DONE) return true;
        if (fixed == null) start(client);
        require(System.nanoTime() < deadline, "Detached camera history timed out in " + step + ", fixed="
                + FIXED_HISTORY.accepted + ", free=" + FREE_HISTORY.accepted + ", pose=" + fixedPosition);
        require(client.gameRenderer.mainCamera() == host && CameraRenderScope.current() == null
                && host.position().distanceToSqr(hostPosition) < .001, "Detached view changed the main camera");
        require(fixed.state() != CameraState.FAILED && free.state() != CameraState.FAILED, "Cloud camera failed: "
                + fixed.failure().orElse(null) + " / " + free.failure().orElse(null));
        if (step == Step.INITIAL && FIXED_HISTORY.accepted >= 10 && FREE_HISTORY.accepted >= 10) {
            require(fixedPosition != null && freePosition != null && fixedPosition.distanceToSqr(freePosition) > 1,
                    "Detached cameras shared a camera position");
            target.setPos(target.getX() + 3, target.getY(), target.getZ());
            target.setYHeadRot(12);
            target.yHeadRotO = 12;
            target.setOldPosAndRot();
            step = Step.MOVED;
        } else if (step == Step.MOVED && fixedPosition != null
                && fixedPosition.distanceToSqr(target.getEyePosition()) < .001 && Math.abs(fixedYaw - 12) < .1f) {
            fixed.close();
            FREE_HISTORY.accepted = 0;
            step = Step.ONE_CLOSED;
        } else if (step == Step.ONE_CLOSED && FREE_HISTORY.accepted >= 10) {
            free.close();
            CloudEffects.sky().settings(savedSky);
            CloudEffects.setSkyEnabled(savedSkyEnabled);
            if (savedEnabled) CloudEffects.setEnabled(true);
            else CloudEffects.shutdown();
            step = Step.DONE;
            LogUtils.getLogger().info("KASUGA_CAMERA_HISTORY_PASS fixed-entity-follow,main-camera-isolation,two-sizes,independent-history");
        }
        return step == Step.DONE;
    }

    private static void start(Minecraft client) {
        deadline = System.nanoTime() + 20_000_000_000L;
        host = client.gameRenderer.mainCamera();
        hostPosition = host.position();
        target = new ArmorStand(client.level, hostPosition.x + 2, hostPosition.y - 1.75, hostPosition.z);
        target.setOldPosAndRot();
        savedSky = CloudEffects.sky().settings();
        savedEnabled = CloudEffects.isEnabled();
        savedSkyEnabled = CloudEffects.isSkyEnabled();
        float base = (float) hostPosition.y - 8;
        SkyCloudRendering optics = SkyCloudRendering.DEFAULT.withDensityDetail(.055f, .18f, 4)
                .withSampling(64, 2, 3, true).withDebug(SkyCloudRendering.DebugView.HISTORY_VALIDITY);
        CloudEffects.sky().settings(new SkyCloudSettings(124, 128, base, 75, 1, 768, 0, 0,
                SkyCloudSettings.Quality.FAST, List.of(
                new SkyCloudLayer(SkyCloudLayer.Type.CUMULUS, base, 48, 128, .3f, 4),
                new SkyCloudLayer(SkyCloudLayer.Type.CIRRUS, base + 48, 40, 192, .2f, 3)), optics));
        CloudEffects.setEnabled(true);
        CloudEffects.setSkyEnabled(true);
        CameraRenderSettings settings = new CameraRenderSettings(2, CameraRenderSettings.Quality.FAST,
                CameraRenderSettings.Shader.disabled());
        fixed = MinecraftCameras.createFixed(FIXED, target, PROJECTION, settings, FIXED_HISTORY::sample);
        free = MinecraftCameras.createFree(FREE, new WorldCameraView(hostPosition.x - 7, hostPosition.y,
                hostPosition.z, 8, 0, 0, 60, 96, 72), settings, FREE_HISTORY::sample);
    }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        String view = CameraRenderScope.currentViewId();
        if (view.equals(FIXED)) {
            fixedPosition = event.getCamera().position();
            fixedYaw = event.getCamera().yRot();
        } else if (view.equals(FREE)) {
            freePosition = event.getCamera().position();
        }
    }

    private static final class HistoryProbe {
        private final int width;
        private final int height;
        private boolean pending;
        private int accepted;

        HistoryProbe(int width, int height) { this.width = width; this.height = height; }

        void sample(OutputFrame<GpuTextureView> frame) {
            require(frame.width() == width && frame.height() == height, "Cloud camera returned another view's dimensions");
            if (pending || frame.frameNumber() % 5 != 0) return;
            Step phase = step;
            TextureTarget readback = new TextureTarget("Detached camera history", width, height, GpuFormat.RGBA8_UNORM, null);
            try {
                PostProcessContext.copyColor(frame.resource(), readback.getColorTextureView(), false);
                pending = true;
                Screenshot.takeScreenshot(readback, image -> {
                    try (image) {
                        if (phase != step) return;
                        int green = 0;
                        for (int y = 0; y < height; y += 2) {
                            for (int x = 0; x < width; x += 2) {
                                int pixel = image.getPixel(x, y);
                                if ((pixel >>> 8 & 255) > 210 && (pixel >>> 16 & 255) < 45 && (pixel & 255) < 45) green++;
                            }
                        }
                        accepted = green;
                    } finally {
                        pending = false;
                    }
                });
            } finally {
                readback.destroyBuffers();
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
