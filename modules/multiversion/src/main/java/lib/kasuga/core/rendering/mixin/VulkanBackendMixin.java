package lib.kasuga.core.rendering.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import lib.kasuga.rendering.batch.VulkanIndexedVisibility;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashSet;
import java.util.Set;

@Mixin(value = VulkanBackend.class, remap = false)
abstract class VulkanBackendMixin {
    @WrapOperation(method = "createDevice", at = @At(value = "INVOKE", target =
            "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;"))
    private VkDevice kasuga$retryWithoutIndirectCount(FeatureSet requested, VulkanPhysicalDevice physicalDevice,
                                                       Operation<VkDevice> original, @Local LocalRef<FeatureSet> enabled) throws Exception {
        try {
            return original.call(requested, physicalDevice);
        } catch (Exception failure) {
            FeatureSet indirectCount = VulkanIndexedVisibility.indirectCountFeature();
            // RenderPearl includes the Vulkan result name in device creation errors.
            if (!(failure instanceof BackendCreationException backendFailure)
                    || backendFailure.getReason() != BackendCreationException.Reason.VULKAN_NO_DEVICE
                    || !backendFailure.getMessage().startsWith("VK_ERROR_FEATURE_NOT_PRESENT:")
                    || !requested.contains(indirectCount)) throw failure;

            Set<VulkanFeature> features = new HashSet<>(requested.features());
            features.removeAll(indirectCount.features());
            FeatureSet fallback = new FeatureSet(requested.name(), requested.extensions(), features, requested.condition());
            LogUtils.getLogger().warn("Vulkan rejected drawIndirectCount during device creation. Retrying without it.");
            try {
                VkDevice device = original.call(fallback, physicalDevice);
                enabled.set(fallback);
                return device;
            } catch (Exception retryFailure) {
                retryFailure.addSuppressed(failure);
                throw retryFailure;
            }
        }
    }
}
