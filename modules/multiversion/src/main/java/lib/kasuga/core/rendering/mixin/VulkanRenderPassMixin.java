package lib.kasuga.core.rendering.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import lib.kasuga.rendering.batch.VulkanIndexedVisibility;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = VulkanRenderPass.class, remap = false)
abstract class VulkanRenderPassMixin {
    // Run after RenderPearl's frontend validation and backend descriptor binding.
    @WrapOperation(method = "drawIndexedIndirect", at = @At(value = "INVOKE", target =
            "Lorg/lwjgl/vulkan/VK12;vkCmdDrawIndexedIndirect(Lorg/lwjgl/vulkan/VkCommandBuffer;JJII)V"))
    private void kasuga$drawIndexedIndirect(VkCommandBuffer command, long buffer, long offset, int drawCount, int stride,
                                           Operation<Void> original, @Local(argsOnly = true) GpuBufferSlice commands) {
        if (VulkanIndexedVisibility.tryDrawCounted(command, commands, drawCount, stride)) return;
        original.call(command, buffer, offset, drawCount, stride);
    }
}
