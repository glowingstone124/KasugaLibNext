package lib.kasuga.core.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.content.document.DocumentItem;
import lib.kasuga.content.document.DocumentItemRenderer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

public final class SpecialRenderingRegistry {
    private static final Map<Item, ISpecialItemRenderer> RENDERERS = new HashMap<>();
    private static final DocumentItemRenderer DOCUMENTS = new DocumentItemRenderer();

    private SpecialRenderingRegistry() {}

    public static <T extends Item> void register(T item, ISpecialItemRenderer renderer) {
        RENDERERS.put(item, renderer);
    }

    public static boolean render(FirstPersonHandsAndItemsRenderer hands, PlayerRenderState player,
                                 float partialTick, float pitch, InteractionHand hand, float swingProgress,
                                 ItemStack stack, float equipProgress, PoseStack poseStack,
                                 SubmitNodeCollector nodes, int packedLight) {
        ISpecialItemRenderer renderer = RENDERERS.get(stack.getItem());
        if (renderer == null && stack.getItem() instanceof DocumentItem) renderer = DOCUMENTS;
        if (renderer == null) return false;
        poseStack.pushPose();
        try {
            renderer.render(hands, player, partialTick, pitch, hand, swingProgress, stack,
                    equipProgress, poseStack, nodes, packedLight);
        } finally {
            poseStack.popPose();
        }
        return true;
    }
}
