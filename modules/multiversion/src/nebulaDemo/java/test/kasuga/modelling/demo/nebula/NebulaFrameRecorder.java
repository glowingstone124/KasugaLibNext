package test.kasuga.modelling.demo.nebula;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.FrameOutputRouter;
import lib.kasuga.rendering.output.OutputFrame;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Records a fixed number of completed frames without accumulating unbounded GPU readbacks. */
final class NebulaFrameRecorder implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int READBACK_SLOTS = 3;
    private final Path directory;
    private final Path partialVideo;
    private final int targetFrames;
    private final int width;
    private final int height;
    private final List<GpuBuffer> buffers = new ArrayList<>(READBACK_SLOTS);
    private final ArrayDeque<GpuBuffer> available = new ArrayDeque<>(READBACK_SLOTS);
    private final Process encoder;
    private final WritableByteChannel video;
    private final FrameOutputRouter<GpuTextureView>.Registration output;
    private boolean requested;
    private boolean closed;
    private int submitted;
    private int encoded;
    private float openingDistance;
    private float endingDistance;
    private Exception failure;

    NebulaFrameRecorder(Path directory, int targetFrames, int fps, int width, int height, int timeoutSeconds) throws IOException {
        this.directory = directory;
        this.targetFrames = targetFrames;
        this.width = width;
        this.height = height;
        if ((width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("Nebula video requires even framebuffer dimensions for YUV420");
        }
        Files.createDirectories(directory);
        partialVideo = directory.resolve("nebula-sim.partial.mp4");
        long bytes = Math.multiplyExact(Math.multiplyExact((long) width, height), 4);
        encoder = new ProcessBuilder("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "warning", "-y",
                "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", width + "x" + height,
                "-framerate", Integer.toString(fps), "-i", "pipe:0", "-vf", "vflip",
                "-c:v", "libx264", "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                partialVideo.toString()).redirectError(directory.resolve("ffmpeg.log").toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        encoder.onExit().orTimeout(timeoutSeconds, TimeUnit.SECONDS).exceptionally(error -> {
            if (encoder.isAlive()) encoder.destroyForcibly();
            return null;
        });
        video = Channels.newChannel(encoder.getOutputStream());
        try {
            for (int i = 0; i < READBACK_SLOTS; i++) {
                GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "Nebula video readback",
                        GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
                buffers.add(buffer);
                available.add(buffer);
            }
            output = MinecraftFrameOutputs.open(FrameOutputMode.OFFSCREEN_ONLY, this::capture);
        } catch (RuntimeException | Error error) {
            encoder.destroyForcibly();
            try { video.close(); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            buffers.forEach(GpuBuffer::close);
            throw error;
        }
    }

    /** Only an accepted frame advances the fixed simulation clock. */
    boolean requestFrame() {
        if (requested) throw new IllegalStateException("The preceding Nebula frame was not published");
        requested = failure == null && submitted < targetFrames && !available.isEmpty();
        return requested;
    }

    private void capture(OutputFrame<GpuTextureView> frame) {
        if (!requested) return;
        requested = false;
        try {
            if (frame.width() != width || frame.height() != height
                    || frame.resource().texture().getFormat() != GpuFormat.RGBA8_UNORM) {
                throw new IllegalStateException("Nebula video requires a stable RGBA8 framebuffer");
            }
            GpuBuffer buffer = available.removeFirst();
            int frameNumber = ++submitted;
            if (frameNumber == 1) openingDistance = NebulaDemo.cameraDistance();
            if (frameNumber == targetFrames) endingDistance = NebulaDemo.cameraDistance();
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(
                    frame.resource().texture(), buffer, 0, () -> frameReady(buffer, frameNumber), 0);
        } catch (RuntimeException error) {
            failure = error;
        }
    }

    private void frameReady(GpuBuffer buffer, int frameNumber) {
        if (closed || failure != null) return;
        try (GpuBufferSlice.MappedView read = buffer.map(true, false)) {
            if (frameNumber != encoded + 1) throw new IllegalStateException("Nebula GPU readbacks arrived out of order");
            ByteBuffer data = read.data();
            if (frameNumber == 1) savePng(data, "opening-shot.png");
            if (frameNumber == targetFrames) savePng(data, "ending-shot.png");
            while (data.hasRemaining()) video.write(data);
            encoded++;
            int interval = Math.max(1, targetFrames / 100);
            if (encoded == 1 || encoded == targetFrames || encoded % interval == 0) {
                LOGGER.info("NEBULA_RENDER_PROGRESS {}% ({}/{})", Math.max(1, 100 * encoded / targetFrames), encoded, targetFrames);
            }
            if (encoded == targetFrames) video.close();
        } catch (IOException | RuntimeException error) {
            failure = error;
        } finally {
            available.addLast(buffer);
        }
    }

    private void savePng(ByteBuffer rgba, String name) throws IOException {
        try (NativeImage image = new NativeImage(width, height, false)) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    image.setPixelABGR(x, height - 1 - y, rgba.getInt((y * width + x) * 4) | 0xff000000);
                }
            }
            image.writeToFile(directory.resolve(name));
        }
    }

    boolean complete() throws IOException {
        if (failure != null) throw new IOException("Nebula frame capture failed", failure);
        if (output.isClosed()) throw new IOException("Nebula frame output was detached before recording finished");
        if (encoder.isAlive()) return false;
        if (encoded != targetFrames || encoder.exitValue() != 0) {
            throw new IOException("FFmpeg stopped after " + encoded + " frames. See " + directory.resolve("ffmpeg.log"));
        }
        Files.move(partialVideo, directory.resolve("nebula-sim.mp4"), StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    int encoded() { return encoded; }
    float openingDistance() { return openingDistance; }
    float endingDistance() { return endingDistance; }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (encoder.isAlive()) encoder.destroyForcibly();
        try {
            try { video.close(); }
            finally { output.close(); }
        } catch (Exception error) {
            throw new IOException("Could not release the Nebula frame recorder", error);
        } finally {
            buffers.forEach(GpuBuffer::close);
        }
    }
}
