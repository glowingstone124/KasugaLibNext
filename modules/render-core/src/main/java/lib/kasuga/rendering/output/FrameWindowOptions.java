package lib.kasuga.rendering.output;

import java.util.Objects;

/** Initial size is in window coordinates. Framebuffers can use a different pixel size. */
public record FrameWindowOptions(String title, int width, int height, boolean visible) {
    public FrameWindowOptions {
        Objects.requireNonNull(title, "title");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Window dimensions must be positive");
    }

    public FrameWindowOptions(String title, int width, int height) {
        this(title, width, height, true);
    }
}
