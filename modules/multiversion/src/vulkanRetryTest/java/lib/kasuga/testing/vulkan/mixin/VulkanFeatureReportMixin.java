package lib.kasuga.testing.vulkan.mixin;

import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.backend.vulkan.VulkanUtils;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import lib.kasuga.rendering.batch.VulkanIndexedVisibility;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(value = VulkanUtils.class, remap = false)
abstract class VulkanFeatureReportMixin {
    @Inject(method = "enumerateFeatures", at = @At("RETURN"))
    private static void kasugaTest$reportDrawIndirectCount(VkPhysicalDevice physicalDevice, Set<VulkanFeature> queried,
                                                          MemoryStack stack, CallbackInfoReturnable<VkPhysicalDeviceFeatures2> callback) {
        VulkanFeature count = VulkanIndexedVisibility.indirectCountFeature().features().iterator().next();
        if (!queried.contains(count)) return;

        VkPhysicalDeviceFeatures2 reported = callback.getReturnValue();
        boolean driverSupported = count.get(reported);
        if (!count.set(reported, true)) throw new IllegalStateException("Vulkan 1.2 feature query struct missing");
        LogUtils.getLogger().info("KASUGA_VULKAN_RETRY_REPORT drawIndirectCount=true driverSupported={}", driverSupported);
    }
}
