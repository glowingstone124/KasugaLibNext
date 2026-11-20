package lib.kasuga.core.rendering.mixin;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;
import lib.kasuga.rendering.output.mc.MinecraftFrameWindows;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftFrameOutputMixin {
    @Shadow @Final public GameRenderer gameRenderer;
    @Shadow @Final private GpuSurface windowSurface;
    @Unique private boolean kasuga$exclusiveOutput;

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/neoforged/neoforge/client/ClientHooks;fireRenderFramePost(Lnet/minecraft/client/DeltaTracker;)V"))
    private void kasuga$publishFrame(boolean advanceGameTime, CallbackInfo ci) {
        MinecraftCameras.renderDetachedViews();
        kasuga$exclusiveOutput = MinecraftFrameOutputs.publishMain(gameRenderer.mainRenderTarget());
    }

    @ModifyArg(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;blitFromTexture(Lcom/mojang/renderpearl/api/commands/CommandEncoder;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"),
            index = 1)
    private GpuTextureView kasuga$presentFrame(GpuTextureView color) {
        if (!kasuga$exclusiveOutput) return color;
        return MinecraftFrameOutputs.blankPresentation(windowSurface, color);
    }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V"))
    private void kasuga$presentPreviewWindows(boolean advanceGameTime, CallbackInfo ci) {
        MinecraftFrameWindows.presentSubmittedFrames();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void kasuga$closeFrameOutputs(CallbackInfo ci) {
        MinecraftCameras.shutdown();
        MinecraftFrameWindows.shutdown();
        MinecraftFrameOutputs.shutdown();
    }
}
