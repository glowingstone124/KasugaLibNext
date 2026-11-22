package test.kasuga.modelling.demo.nebula;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Runs the same visual checks in the graphics suite and the standalone recorder. */
public final class NebulaVerification implements AutoCloseable {
    private enum Step { ENTER, TIMING, SILHOUETTE, MATERIAL, VOLUME, VIDEO, RETURN, DONE }

    private final Path output;
    private final Map<String, Object> report;
    private final int targetFrames;
    private final int fps;
    private final int timeoutSeconds;
    private final int expectedWidth;
    private final int expectedHeight;
    private Step step = Step.ENTER;
    private ResourceKey<Level> original;
    private Vec3 position;
    private boolean originalHud;
    private boolean originalFlying;
    private GameType originalGameMode;
    private float originalYaw;
    private float originalPitch;
    private boolean pending;
    private int afterDraw;
    private long started;
    private NebulaGpuTiming timing;
    private NebulaFrameRecorder recorder;

    public NebulaVerification() {
        this(null, null, 0, 30, 45, 0, 0);
    }

    NebulaVerification(Path output, Map<String, Object> report, int targetFrames, int fps, int timeoutSeconds,
                       int expectedWidth, int expectedHeight) {
        this.output = output;
        this.report = report;
        this.targetFrames = targetFrames;
        this.fps = fps;
        this.timeoutSeconds = timeoutSeconds;
        this.expectedWidth = expectedWidth;
        this.expectedHeight = expectedHeight;
    }

    public boolean needsFrames() { return step != Step.DONE; }

    public void afterFrame(Minecraft client) throws IOException {
        if (original == null) {
            original = client.level.dimension();
            position = client.player.position();
            originalHud = client.gui.hud.isHidden();
            originalFlying = client.player.getAbilities().flying;
            originalGameMode = client.gameMode.getPlayerMode();
            originalYaw = client.player.getYRot();
            originalPitch = client.player.getXRot();
            started = System.nanoTime();
            if (!NebulaDemo.start()) throw new IllegalStateException("Nebula command did not start");
        }
        if (System.nanoTime() - started > timeoutSeconds * 1_000_000_000L) {
            throw new IllegalStateException("Nebula smoke timed out at " + step + ", position "
                    + (client.player == null ? null : client.player.position()) + ", expected return " + position);
        }
        if (step == Step.ENTER && NebulaDemo.active()) {
            NebulaDemo.capture(true);
            if (report == null) moveTo(Step.SILHOUETTE, 1);
            else {
                timing = NebulaDemo.measureGpuTime();
                step = Step.TIMING;
            }
        }
        if (step == Step.TIMING) {
            if (timing.samples() < NebulaGpuTiming.REQUIRED_SAMPLES) return;
            boolean enforce = Boolean.parseBoolean(System.getProperty("kasuga.nebula.enforceGpuBudget", "true"));
            report.put("gpuAsyncSamples", timing.samples());
            report.put("gpuPassMeanMs", timing.meanMs());
            report.put("gpuPassMaxMs", timing.maxMs());
            report.put("gpuBudgetEnforced", enforce);
            if (enforce && timing.meanMs() >= 45) {
                throw new IllegalStateException("GPU volume pass remained too slow (" + timing.meanMs() + " ms)");
            }
            NebulaDemo.stopGpuTiming();
            timing = null;
            moveTo(Step.SILHOUETTE, 1);
        }
        if (step == Step.VIDEO) {
            if (!NebulaDemo.active()) throw new IllegalStateException("Nebula closed during frame capture");
            if (recorder == null) beginRecording(client.gameRenderer.mainRenderTarget());
            else if (recorder.complete()) finishRecording();
            return;
        }
        if (step == Step.RETURN) {
            if (!NebulaDemo.idle() || client.level == null || client.player == null
                    || !client.level.dimension().equals(original) || client.gui.screen() != null
                    || client.gui.overlay() != null) return;
            // The dimension-change packet may arrive a frame ahead of the position update.
            if (client.player.position().distanceToSqr(position) > 4) return;
            if (client.gui.hud.isHidden() != originalHud) throw new IllegalStateException("Nebula did not restore the viewer HUD");
            if (client.gameMode.getPlayerMode() != originalGameMode || client.player.getAbilities().flying != originalFlying
                    || Math.abs(Mth.wrapDegrees(client.player.getYRot() - originalYaw)) > .01f
                    || Math.abs(client.player.getXRot() - originalPitch) > .01f) return;
            step = Step.DONE;
            if (report != null) report.put("viewerRestored", true);
            LogUtils.getLogger().info("KASUGA_NEBULA_PASS obj-pbr,mrt,reverse-z,volume,warp,return");
            return;
        }
        if (step == Step.ENTER || pending || NebulaDemo.drawCount() < afterDraw) return;
        pending = true;
        Step sampled = step;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), pixels -> {
            int nonBlack;
            int bright;
            int colored;
            try (pixels) {
                nonBlack = count(pixels, 3, false);
                bright = count(pixels, 245, false);
                colored = count(pixels, 10, true);
                if (output != null) pixels.writeToFile(output.resolve(sampled.name().toLowerCase(java.util.Locale.ROOT) + ".png"));
                else if (sampled == Step.VOLUME) pixels.writeToFile(client.gameDirectory.toPath().resolve("kasuga-nebula-flight.png"));
            } catch (IOException failure) {
                throw new IllegalStateException("Could not save Nebula capture", failure);
            }
            client.execute(() -> validate(sampled, nonBlack, bright, colored));
        });
    }

    private void validate(Step sampled, int nonBlack, int bright, int colored) {
        LogUtils.getLogger().info("KASUGA_NEBULA_CAPTURE phase={} lit={} bright={} color={}",
                sampled, nonBlack, bright, colored);
        switch (sampled) {
            case SILHOUETTE -> {
                if (NebulaDemo.triangleCount() < 6000 || bright < 500 || bright != nonBlack) {
                    throw new IllegalStateException("Nebula G-buffer depth did not produce a starship silhouette");
                }
                moveTo(Step.MATERIAL, 2);
            }
            case MATERIAL -> {
                if (nonBlack < 500 || colored < 300) throw new IllegalStateException("Nebula hull PBR shading is missing");
                if (!NebulaDemo.warp()) throw new IllegalStateException("Nebula warp FSM did not accept a trigger");
                moveTo(Step.VOLUME, 0);
            }
            case VOLUME -> {
                if (nonBlack < 5000 || colored < 2000) throw new IllegalStateException("Nebula volume produced no visible output");
                if (targetFrames > 0) step = Step.VIDEO;
                else restoreViewer();
            }
            default -> throw new IllegalStateException("Unexpected Nebula sample " + sampled);
        }
    }

    private void moveTo(Step next, int debug) {
        pending = false;
        step = next;
        NebulaDemo.probe(debug);
        afterDraw = NebulaDemo.drawCount() + 3;
    }

    private void beginRecording(RenderTarget target) throws IOException {
        if (target.width != expectedWidth || target.height != expectedHeight) {
            throw new IllegalStateException("Requested " + expectedWidth + "x" + expectedHeight
                    + " but the framebuffer was " + target.width + "x" + target.height);
        }
        recorder = new NebulaFrameRecorder(output, targetFrames, fps, target.width, target.height, timeoutSeconds);
        NebulaDemo.record(recorder::requestFrame);
        report.put("nebula", Map.of("width", target.width, "height", target.height));
        report.put("recordFrequencyHz", NebulaDemo.frequency());
        report.put("recordFps", fps);
    }

    private void finishRecording() throws IOException {
        double simulatedSeconds = NebulaDemo.sceneTime();
        if (Math.abs(simulatedSeconds - (double) targetFrames / fps) > .01) {
            throw new IllegalStateException("Nebula did not advance its clock once per recorded frame");
        }
        float openingDistance = recorder.openingDistance();
        float endingDistance = recorder.endingDistance();
        if (targetFrames >= 15 * fps) {
            if (endingDistance < openingDistance * 3) throw new IllegalStateException("Recorded camera did not pull back");
            report.put("recordedNearToWide", true);
        }
        report.put("openingCameraDistance", openingDistance);
        report.put("endingCameraDistance", endingDistance);
        report.put("videoFrames", recorder.encoded());
        report.put("recordSimulatedSeconds", simulatedSeconds);
        report.put("recordLightningCount", NebulaDemo.lightningCount());
        report.put("renderProgressPercent", 100);
        recorder.close();
        recorder = null;
        restoreViewer();
    }

    private void restoreViewer() {
        NebulaDemo.stop();
        step = Step.RETURN;
    }

    @Override public void close() throws IOException {
        try {
            if (recorder != null) recorder.close();
        } finally {
            NebulaDemo.stop();
        }
    }

    private static int count(NativeImage image, int threshold, boolean color) {
        int count = 0;
        for (int y = image.getHeight() / 12; y < image.getHeight() * 11 / 12; y++) {
            for (int x = image.getWidth() / 12; x < image.getWidth() * 11 / 12; x++) {
                int pixel = image.getPixel(x, y);
                int red = pixel >>> 16 & 255;
                int green = pixel >>> 8 & 255;
                int blue = pixel & 255;
                int max = Math.max(red, Math.max(green, blue));
                int min = Math.min(red, Math.min(green, blue));
                if (max >= threshold && (!color || max - min >= threshold)) count++;
            }
        }
        return count;
    }
}
