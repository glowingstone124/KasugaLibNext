package lib.kasuga.core.rendering.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import lib.kasuga.rendering.batch.VulkanIndexedVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;

@Mixin(value = VulkanFeatureSets.class, remap = false)
abstract class VulkanFeatureSetsMixin {
    @Inject(method = "optionalFeatureSets", at = @At("RETURN"), cancellable = true, require = 0)
    private static void kasuga$requestDrawIndirectCount(CallbackInfoReturnable<Set<FeatureSet>> callback) {
        if (VulkanIndexedVisibility.isIndirectCountDisabled()) return;
        Set<FeatureSet> optional = new HashSet<>(callback.getReturnValue());
        optional.add(VulkanIndexedVisibility.indirectCountFeature());
        callback.setReturnValue(optional);
    }
}
