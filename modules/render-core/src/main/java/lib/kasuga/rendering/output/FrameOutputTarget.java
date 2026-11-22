package lib.kasuga.rendering.output;

import java.util.function.Consumer;

/**
 * Owns destination storage and renders the final presentation into it, resizing
 * to the requested pixel dimensions. Must restore the backend's render target
 * and viewport on success and failure. Closing retires storage; callers never close
 * the returned resource. The router shares it read-only between view consumers,
 * which must restore any other backend state they change.
 */
public interface FrameOutputTarget<T> extends AutoCloseable {
    T capture(FrameBlitter blitter, int width, int height);

    /** Gives an explicit destination to backends that record copies or render passes. */
    interface Writable<T> extends FrameOutputTarget<T> {
        T captureInto(Consumer<? super T> writer, int width, int height);

        @Override
        default T capture(FrameBlitter blitter, int width, int height) {
            return captureInto(destination -> blitter.draw(width, height), width, height);
        }
    }

    /** Allocates the snapshot using the format of a backend-owned source resource. */
    interface Copying<T> extends FrameOutputTarget<T> {
        T captureFrom(T source, int width, int height);

        @Override
        default T capture(FrameBlitter blitter, int width, int height) {
            throw new UnsupportedOperationException("This frame target requires a source resource");
        }
    }
}
