package lib.kasuga.core.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

@FunctionalInterface
public interface ISpecialItemRenderer {
    void render(FirstPersonHandsAndItemsRenderer hands, PlayerRenderState player,
                float partialTick, float pitch, InteractionHand hand, float swingProgress, ItemStack stack,
                float equipProgress, PoseStack poseStack, SubmitNodeCollector nodes, int packedLight);
}
