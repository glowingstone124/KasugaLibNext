package lib.kasuga.core.rendering.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import lib.kasuga.rendering.output.mc.CameraRenderScope;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
abstract class CameraGameRendererMixin {
    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void kasuga$cameraTarget(CallbackInfoReturnable<RenderTarget> callback) {
        CameraRenderScope scope = CameraRenderScope.current();
        if (scope != null) callback.setReturnValue(scope.target());
    }

    @Inject(method = "renderBuffers", at = @At("HEAD"), cancellable = true)
    private void kasuga$cameraBuffers(CallbackInfoReturnable<RenderBuffers> callback) {
        CameraRenderScope scope = CameraRenderScope.current();
        if (scope != null) callback.setReturnValue(scope.buffers());
    }

    @Inject(method = "featureRenderDispatcher", at = @At("HEAD"), cancellable = true)
    private void kasuga$cameraFeatures(CallbackInfoReturnable<FeatureRenderDispatcher> callback) {
        CameraRenderScope scope = CameraRenderScope.current();
        if (scope != null) callback.setReturnValue(scope.features());
    }

    @Inject(method = "gameRenderState", at = @At("HEAD"), cancellable = true)
    private void kasuga$cameraState(CallbackInfoReturnable<GameRenderState> callback) {
        CameraRenderScope scope = CameraRenderScope.current();
        if (scope != null) callback.setReturnValue(scope.state());
    }

    @Inject(method = "mainCamera", at = @At("HEAD"), cancellable = true)
    private void kasuga$cameraPose(CallbackInfoReturnable<Camera> callback) {
        CameraRenderScope scope = CameraRenderScope.current();
        if (scope != null) callback.setReturnValue(scope.camera());
    }

    @Inject(method = "useImprovedTransparency", at = @At("HEAD"), cancellable = true)
    private void kasuga$cameraTransparency(CallbackInfoReturnable<Boolean> callback) {
        CameraRenderScope scope = CameraRenderScope.current();
        if (scope != null) callback.setReturnValue(scope.state().optionsRenderState.improvedTransparency
                && !scope.state().levelRenderState.renderWireframeTerrain);
    }
}
