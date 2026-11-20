package lib.kasuga.core.rendering.mixin;

import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraViewAccess {
    @Accessor("initialized") void kasuga$setInitialized(boolean initialized);
    @Accessor("detached") void kasuga$setDetached(boolean detached);
    @Accessor("depthFar") void kasuga$setDepthFar(float depthFar);
    @Accessor("fov") void kasuga$setFov(float fov);
    @Invoker("setupPerspective") void kasuga$setPerspective(float near, float far, float fov, float width, float height);
    @Invoker("prepareCullFrustum") void kasuga$prepareFrustum(Matrix4fc view, Matrix4f projection, Vec3 position);
}
