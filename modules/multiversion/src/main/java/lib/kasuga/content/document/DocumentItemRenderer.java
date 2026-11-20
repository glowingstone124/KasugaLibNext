package lib.kasuga.content.document;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import lib.kasuga.core.rendering.ISpecialItemRenderer;
import lib.kasuga.core.rendering.mixin.FirstPersonRendererAccess;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.core.Holder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Submits the page and its registered components through the first-person render pipeline. */
public class DocumentItemRenderer implements ISpecialItemRenderer {
    private static final Map<DocumentComponentType<?>, DocumentComponentRenderer<?>> RENDERERS = new HashMap<>();

    @Override
    public void render(FirstPersonHandsAndItemsRenderer hands, PlayerRenderState player, float partialTick,
                       float pitch, InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
                       PoseStack pose, SubmitNodeCollector nodes, int packedLight) {
        FirstPersonRendererAccess arms = (FirstPersonRendererAccess) hands;
        HumanoidArm mainArm = player.avatarRenderState.mainArm;
        if (hand == InteractionHand.MAIN_HAND && player.firstPersonHandsAndItems.offHandItem.isEmpty()) {
            DocumentLayout.twoHandPose(pose, pitch, equipProgress, swingProgress);
            if (!player.avatarRenderState.isInvisible) {
                pose.pushPose();
                pose.rotateDegrees(Axis.YP, 90f);
                arms.kasuga$renderMapHand(pose, nodes, packedLight, HumanoidArm.LEFT, player);
                arms.kasuga$renderMapHand(pose, nodes, packedLight, HumanoidArm.RIGHT, player);
                pose.popPose();
            }
            DocumentLayout.twoHandPage(pose, swingProgress);
        } else {
            HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? mainArm : mainArm.getOpposite();
            float side = arm == HumanoidArm.RIGHT ? 1f : -1f;
            pose.translate(side * .125f, -.125f, 0f);
            if (!player.avatarRenderState.isInvisible) {
                pose.pushPose();
                pose.rotateDegrees(Axis.ZP, side * 10f);
                arms.kasuga$renderPlayerArm(pose, nodes, packedLight, equipProgress, swingProgress, arm, player);
                pose.popPose();
            }
            DocumentLayout.oneHandPage(pose, side, equipProgress, swingProgress);
        }
        renderItem(stack, nodes, pose, packedLight);
    }

    public void renderItem(ItemStack stack, SubmitNodeCollector nodes, PoseStack pose, int packedLight) {
        Map<Holder<DocumentComponentType<?>>, Object> components =
                stack.get(DocumentComponentRegistries.DOCUMENT_COMPONENT.getEntry());
        if (components == null) return;
        DocumentLayout.pageCoordinates(pose);
        renderBackground(stack, nodes, pose, packedLight);

        for (Map.Entry<Holder<DocumentComponentType<?>>, Object> entry : components.entrySet()) {
            DocumentComponentRenderer<?> renderer = RENDERERS.get(entry.getKey().value());
            if (renderer == null) continue;
            pose.pushPose();
            try {
                renderComponent(renderer, entry.getValue(), stack, nodes, pose, packedLight);
            } finally {
                pose.popPose();
            }
        }
    }

    protected void renderBackground(ItemStack stack, SubmitNodeCollector nodes, PoseStack pose, int packedLight) {}

    public static <T> void registerComponentRenderer(DocumentComponentType<T> type,
                                                       DocumentComponentRenderer<T> renderer) {
        RENDERERS.put(type, renderer);
    }

    @SuppressWarnings("unchecked")
    private static <T> void renderComponent(DocumentComponentRenderer<T> renderer, Object value,
                                            ItemStack stack, SubmitNodeCollector nodes, PoseStack pose, int light) {
        renderer.render((T) value, stack, nodes, pose, light);
    }
}
