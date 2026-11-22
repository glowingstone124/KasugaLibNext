package lib.kasuga.core.rendering.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.Window;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuSurface;
import lib.kasuga.rendering.output.mc.HeadlessClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

@Mixin(Minecraft.class)
abstract class HeadlessMinecraftMixin {
    @Inject(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;initBackendSystem()Lnet/minecraft/util/TimeSource$NanoTimeSource;"))
    private void kasuga$selectVideoDriver(CallbackInfo callback) { HeadlessClient.initializePlatform(); }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/Window;<init>(Lcom/mojang/blaze3d/platform/WindowEventHandler;Lcom/mojang/blaze3d/platform/DisplayData;Ljava/lang/String;ZLjava/lang/String;Lcom/mojang/blaze3d/platform/MonitorManager;Lcom/mojang/renderpearl/api/device/GpuBackend;I)V"),
            index = 1)
    private DisplayData kasuga$windowed(DisplayData display) {
        return HeadlessClient.enabled() ? display.withFullscreen(false) : display;
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createSurface(JLjava/util/function/BooleanSupplier;)Lcom/mojang/renderpearl/api/device/GpuSurface;"))
    private GpuSurface kasuga$presentation(GpuDevice device, long window, BooleanSupplier iconified, Operation<GpuSurface> original) {
        return HeadlessClient.enabled() ? HeadlessClient.surface() : original.call(device, window, iconified);
    }

    @WrapWithCondition(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/client/loading/earlydisplay/EarlyWindowHandoff;completeWindowHandoff(Lcom/mojang/blaze3d/platform/Window;)V"))
    private boolean kasuga$showWindow(Window window) { return !HeadlessClient.enabled(); }

    @WrapWithCondition(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/Window;updateFullscreenIfChanged()V"))
    private boolean kasuga$applyFullscreen(Window window) { return !HeadlessClient.enabled(); }

    @ModifyExpressionValue(method = "renderFrame", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/FramerateLimitTracker;getFramerateLimit()I"))
    private int kasuga$frameRate(int original) {
        return HeadlessClient.enabled() ? Math.max(1, Integer.getInteger("kasuga.headless.fps", 60)) : original;
    }

    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void kasuga$active(CallbackInfoReturnable<Boolean> callback) {
        if (HeadlessClient.enabled()) callback.setReturnValue(true);
    }
}
