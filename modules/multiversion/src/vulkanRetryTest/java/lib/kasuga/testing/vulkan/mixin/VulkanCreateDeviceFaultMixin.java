package lib.kasuga.testing.vulkan.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import lib.kasuga.rendering.batch.VulkanIndexedVisibility;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.VK_ERROR_FEATURE_NOT_PRESENT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

@Mixin(value = VulkanBackend.class, remap = false)
abstract class VulkanCreateDeviceFaultMixin {
    @Unique private static FeatureSet kasugaTest$firstRequest;
    @Unique private static boolean kasugaTest$retried;

    @WrapOperation(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private static int kasugaTest$rejectFirstCreation(VkPhysicalDevice physicalDevice, VkDeviceCreateInfo info,
                                                      VkAllocationCallbacks allocator, PointerBuffer device,
                                                      Operation<Integer> original, @Local(argsOnly = true) FeatureSet requested) {
        FeatureSet countSet = VulkanIndexedVisibility.indirectCountFeature();
        VulkanFeature count = countSet.features().iterator().next();
        if (kasugaTest$firstRequest == null) {
            if (!requested.contains(countSet) || !count.get(info.pNext())) {
                throw new IllegalStateException("First vkCreateDevice did not request drawIndirectCount");
            }
            kasugaTest$firstRequest = requested;
            LogUtils.getLogger().info("KASUGA_VULKAN_RETRY_INJECT vkCreateDevice=VK_ERROR_FEATURE_NOT_PRESENT");
            return VK_ERROR_FEATURE_NOT_PRESENT;
        }

        if (kasugaTest$retried) throw new IllegalStateException("Expected exactly one Vulkan device retry");
        kasugaTest$retried = true;
        Set<VulkanFeature> expected = new HashSet<>(kasugaTest$firstRequest.features());
        expected.remove(count);
        if (!requested.features().equals(expected)
                || !requested.extensions().equals(kasugaTest$firstRequest.extensions()) || count.get(info.pNext())) {
            throw new IllegalStateException("Vulkan device retry must remove only drawIndirectCount");
        }
        int result = original.call(physicalDevice, info, allocator, device);
        if (result == VK_SUCCESS) {
            LogUtils.getLogger().info("KASUGA_VULKAN_RETRY_DEVICE_PASS vkCreateDevice=VK_SUCCESS drawIndirectCount=false");
        }
        return result;
    }
}
