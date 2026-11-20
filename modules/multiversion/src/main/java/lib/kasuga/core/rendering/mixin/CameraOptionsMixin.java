package lib.kasuga.core.rendering.mixin;

import lib.kasuga.rendering.output.mc.CameraRenderScope;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Options.class)
abstract class CameraOptionsMixin {
    @Inject(method = "getEffectiveRenderDistance", at = @At("HEAD"), cancellable = true)
    private void kasuga$cameraDistance(CallbackInfoReturnable<Integer> callback) {
        CameraRenderScope scope = CameraRenderScope.current();
        if (scope != null) callback.setReturnValue(scope.state().optionsRenderState.renderDistance);
    }
}
