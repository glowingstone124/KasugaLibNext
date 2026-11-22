package lib.kasuga.core.rendering.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import lib.kasuga.rendering.batch.VulkanIndexedVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VulkanDevice.class, remap = false)
abstract class VulkanDeviceCloseMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void kasuga$releaseComputePipeline(CallbackInfo callback) {
        VulkanIndexedVisibility.release((VulkanDevice) (Object) this);
    }
}
