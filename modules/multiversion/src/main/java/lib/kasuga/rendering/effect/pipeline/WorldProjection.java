package lib.kasuga.rendering.effect.pipeline;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jetbrains.annotations.ApiStatus;

/** The current world projection, including camera bob and screen effects. */
public final class WorldProjection {
    private static final Matrix4f PROJECTION = new Matrix4f();

    private WorldProjection() {}

    public static Matrix4fc current() { return PROJECTION; }

    @ApiStatus.Internal
    public static void capture(Matrix4fc projection) { PROJECTION.set(projection); }
}
