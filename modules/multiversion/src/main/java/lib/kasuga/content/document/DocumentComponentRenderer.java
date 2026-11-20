package lib.kasuga.content.document;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.ItemStack;

@FunctionalInterface
public interface DocumentComponentRenderer<T> {
    void render(T componentValue, ItemStack stack, SubmitNodeCollector nodes, PoseStack poseStack, int packedLight);
}
