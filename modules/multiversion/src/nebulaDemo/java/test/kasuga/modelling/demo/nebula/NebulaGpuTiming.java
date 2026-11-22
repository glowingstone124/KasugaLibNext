package test.kasuga.modelling.demo.nebula;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.api.commands.GpuQueryPool;

import java.util.OptionalLong;

/** Measures the volume pass without waiting for a pending GPU timestamp. */
final class NebulaGpuTiming implements AutoCloseable {
    static final int REQUIRED_SAMPLES = 15;
    private final NebulaGpuTimer glTimer;
    private final GpuQueryPool pool;
    private final GpuFence[] pending;
    private int submitted;
    private boolean started;
    private int samples;
    private double totalMs;
    private double maxMs;

    NebulaGpuTiming() {
        if (RenderSystem.getDevice().getDeviceInfo().backendName().equals("OpenGL")) {
            // GL_TIME_ELAPSED is supported on Apple OpenGL where absolute timestamps can read as zero.
            glTimer = new NebulaGpuTimer();
            glTimer.enabled(true);
            pool = null;
            pending = null;
        } else {
            glTimer = null;
            pool = RenderSystem.getDevice().createTimestampQueryPool(REQUIRED_SAMPLES * 2);
            pending = new GpuFence[REQUIRED_SAMPLES];
        }
    }

    void begin() {
        if (glTimer != null) {
            glTimer.begin();
            return;
        }
        for (int slot = 0; slot < submitted; slot++) {
            GpuFence fence = pending[slot];
            if (fence == null || !fence.awaitCompletion(0)) continue;
            OptionalLong[] results = pool.getValues(slot * 2, 2);
            if (results[0].isEmpty() || results[1].isEmpty()) continue;
            double elapsed = (results[1].getAsLong() - results[0].getAsLong())
                    * (double) RenderSystem.getDevice().getDeviceInfo().timestampPeriod() / 1_000_000;
            if (elapsed > 0) {
                samples++;
                totalMs += elapsed;
                maxMs = Math.max(maxMs, elapsed);
            }
            fence.close();
            pending[slot] = null;
        }
        if (submitted == REQUIRED_SAMPLES) return;
        RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, submitted * 2);
        started = true;
    }

    void end() {
        if (glTimer != null) {
            glTimer.end();
            return;
        }
        if (!started) return;
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeTimestamp(pool, submitted * 2 + 1);
        pending[submitted++] = encoder.createFence();
        started = false;
    }

    int samples() { return glTimer == null ? samples : Math.toIntExact(glTimer.samples()); }
    double meanMs() {
        int count = samples();
        return count == 0 ? 0 : (glTimer == null ? totalMs : glTimer.totalMs()) / count;
    }
    double maxMs() { return glTimer == null ? maxMs : glTimer.maxMs(); }

    @Override public void close() {
        if (glTimer != null) {
            glTimer.close();
            return;
        }
        for (GpuFence fence : pending) if (fence != null) fence.close();
        pool.close();
    }
}
