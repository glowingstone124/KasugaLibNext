package lib.kasuga.rendering.effect.particle;

import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

/**
 * Renders one complete instance batch. Backends may expand it into a shared Minecraft buffer today
 * or upload instance data and issue a hardware-instanced draw without changing the particle model.
 */
@FunctionalInterface
public interface ParticleBatchRenderer extends AutoCloseable {
    void render(ParticleInstanceBuffer instances, WorldRenderPipelineContext context);

    /** 26.3 renderer that uploads its batch during native render-buffer preparation. */
    interface Prepared extends ParticleBatchRenderer {
        void prepare(ParticleInstanceBuffer instances, Vec3 eye, Matrix4fc viewRotation);
    }

    @Override
    default void close() {
    }
}
