package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.logging.LogUtils;
import lib.kasuga.host.DocumentSmoke;
import lib.kasuga.rendering.cloud.CloudPose;
import lib.kasuga.rendering.cloud.CloudSettings;
import lib.kasuga.rendering.effect.EffectHandle;
import lib.kasuga.rendering.effect.builtin.cloud.CloudEffects;
import lib.kasuga.rendering.effect.builtin.cloud.CumulonimbusCloud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.phys.Vec3;

final class CloudSmoke {
    private static final int REGION_SIZE = 20;
    private static final CloudSettings FAST = new CloudSettings(2, .27f, .7f, 1, 1, 124, CloudSettings.Quality.FAST);
    private static EffectHandle<CumulonimbusCloud> body;
    private static Stage stage = Stage.BASELINE;
    private static int frames;
    private static long waitingSince;
    private static boolean pending;
    private static int[] baseline;
    private static volatile int[] readback;

    private CloudSmoke() {}
    private enum Stage { BASELINE, FAST_VISIBLE, MOVED, HIGH_VISIBLE, DISABLED, RESUMED, REMOVED, DONE }

    static boolean needsFrames() { return stage != Stage.DONE; }

    static void afterFrame(Minecraft client) {
        int[] sample = readback;
        if (sample != null) {
            readback = null;
            pending = false;
            frames = 0;
            advance(client, sample);
            return;
        }
        if (pending || ++frames < 8) return;
        pending = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            int[] values = new int[REGION_SIZE * REGION_SIZE];
            try (image) {
                for (int y = 0; y < REGION_SIZE; y++) {
                    for (int x = 0; x < REGION_SIZE; x++) {
                        int sampleX = (30 * REGION_SIZE + x * 2 * 20) * image.getWidth() / (100 * REGION_SIZE);
                        int sampleY = (30 * REGION_SIZE + y * 2 * 20) * image.getHeight() / (100 * REGION_SIZE);
                        values[y * REGION_SIZE + x] = image.getPixel(sampleX, sampleY);
                    }
                }
            }
            readback = values;
        });
    }

    private static void advance(Minecraft client, int[] values) {
        int changed = 0;
        if (baseline != null) {
            for (int i = 0; i < values.length; i++) {
                int before = baseline[i], after = values[i];
                int delta = Math.abs((before >>> 16 & 255) - (after >>> 16 & 255))
                        + Math.abs((before >>> 8 & 255) - (after >>> 8 & 255)) + Math.abs((before & 255) - (after & 255));
                if (delta > 50) changed++;
            }
        }
        switch (stage) {
            case BASELINE -> {
                baseline = values;
                Vec3 eye = client.gameRenderer.mainCamera().position();
                CloudEffects.setEnabled(true);
                body = CloudEffects.spawn(new CumulonimbusCloud(
                        new CloudPose(eye.x, eye.y - 5, eye.z + 20, 30, 13, 18, 0), FAST));
                waitingSince = System.nanoTime();
                stage = Stage.FAST_VISIBLE;
            }
            case FAST_VISIBLE -> {
                if (changed < 20 && System.nanoTime() - waitingSince < 12_000_000_000L) return;
                require(changed >= 20 && CloudEffects.visibleCount() == 1, "Reduced-resolution cloud was not visible, pixels=" + changed);
                body.effect().moveBy(150, 0, 0);
                stage = Stage.MOVED;
            }
            case MOVED -> {
                require(changed < 8, "Moving the cloud outside the view left pixels=" + changed);
                body.effect().moveBy(-150, 0, 0);
                body.effect().settings(FAST.withQuality(CloudSettings.Quality.HIGH));
                stage = Stage.HIGH_VISIBLE;
            }
            case HIGH_VISIBLE -> {
                require(changed >= 20, "Full-resolution cloud was not visible, pixels=" + changed);
                CloudEffects.setEnabled(false);
                stage = Stage.DISABLED;
            }
            case DISABLED -> {
                require(changed < 8, "Disabled cloud left pixels=" + changed);
                CloudEffects.setEnabled(true);
                body.effect().settings(FAST);
                stage = Stage.RESUMED;
            }
            case RESUMED -> {
                require(changed >= 20 && body.remove(), "Cloud failed to resume, pixels=" + changed);
                stage = Stage.REMOVED;
            }
            case REMOVED -> {
                require(changed < 8 && !body.isActive() && CloudEffects.visibleCount() == 0,
                        "Removed cloud persisted, pixels=" + changed);
                CloudEffects.shutdown();
                stage = Stage.DONE;
                LogUtils.getLogger().info("KASUGA_CLOUD_VOLUME_PASS texture-atlas,downsample,full-resolution,move,toggle,remove");
                DocumentSmoke.start(client);
            }
            case DONE -> throw new IllegalStateException("Cloud smoke already completed");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
