package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;

/** Redirects the host renderer's view resources while a detached world is being extracted or drawn. */
public record CameraRenderScope(String viewId, Camera camera, RenderTarget target, RenderBuffers buffers,
                                FeatureRenderDispatcher features, GameRenderState state) {
    private static final ScopedValue<CameraRenderScope> CURRENT = ScopedValue.newInstance();

    public static CameraRenderScope current() { return CURRENT.isBound() ? CURRENT.get() : null; }

    public static String currentViewId() {
        CameraRenderScope scope = current();
        return scope == null ? MinecraftFrameOutputs.MAIN_VIEW : scope.viewId();
    }

    <T> T call(ScopedValue.CallableOp<T, RuntimeException> task) {
        RenderSystem.assertOnRenderThread();
        return ScopedValue.where(CURRENT, this).call(task);
    }

    void run(Runnable task) {
        RenderSystem.assertOnRenderThread();
        ScopedValue.where(CURRENT, this).run(task);
    }
}
