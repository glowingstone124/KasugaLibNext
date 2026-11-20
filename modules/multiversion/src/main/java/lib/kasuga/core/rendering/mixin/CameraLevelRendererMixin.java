package lib.kasuga.core.rendering.mixin;

import lib.kasuga.rendering.output.mc.CameraRenderScope;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LevelRenderer.class)
abstract class CameraLevelRendererMixin {
    @ModifyArg(method = "invalidateCompiledGeometry", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionCompiler;<init>(ZZ"
                    + "Lnet/minecraft/client/renderer/block/BlockStateModelSet;"
                    + "Lnet/minecraft/client/renderer/block/FluidStateModelSet;Lnet/minecraft/client/color/block/BlockColors;)V"), index = 0)
    private boolean kasuga$cameraAmbientOcclusion(boolean ambientOcclusion) {
        CameraRenderScope scope = CameraRenderScope.current();
        return scope == null ? ambientOcclusion : scope.state().optionsRenderState.ambientOcclusion;
    }
}
