package lib.kasuga.core.rendering.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VulkanDevice.class, remap = false)
public interface VulkanDeviceFeaturesAccess {
    @Accessor("enabledFeatures")
    FeatureSet kasuga$enabledFeatures();
}
