package lib.kasuga.content.document;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import lib.kasuga.core.rendering.ISpecialItemRenderer;
import lib.kasuga.mixins.ItemInHandRendererAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.core.Holder;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

import static lib.kasuga.content.document.DocumentComponentRegistries.DOCUMENT_COMPONENT;

public class DocumentItemRenderer implements ISpecialItemRenderer {
    @Override
    public void render(
            ItemInHandRenderer itemInHandRenderer,
            AbstractClientPlayer player,
            float partialTicks,
            float pitch,
            InteractionHand hand,
            float swingProgress,
            ItemStack stack,
            float equippedProgress,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int combinedLight
    ) {
        ItemStack offHandItem = player.getItemInHand(InteractionHand.OFF_HAND);
        HumanoidArm humanoidArm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        if (hand == InteractionHand.MAIN_HAND && offHandItem.isEmpty()) {
            renderItemInTwoHand(itemInHandRenderer, poseStack, buffer, combinedLight, equippedProgress, humanoidArm, swingProgress, stack, pitch);
        } else {
            renderItemInOneHand(itemInHandRenderer, poseStack, buffer, combinedLight, equippedProgress, humanoidArm, swingProgress, stack);
        }
    }

    public void renderItemInOneHand(
            ItemInHandRenderer itemInHandRenderer,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            float equippedProgress,
            HumanoidArm hand,
            float swingProgress,
            ItemStack stack
    ) {

        float f = hand == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        poseStack.translate(f * 0.125F, -0.125F, 0.0F);
        //noinspection DataFlowIssue
        if (!Minecraft.getInstance().player.isInvisible()) {
            poseStack.pushPose();
            poseStack.mulPose(Axis.ZP.rotationDegrees(f * 10.0F));
            ((ItemInHandRendererAccess)itemInHandRenderer)
                    .invokeRenderPlayerArm(poseStack, buffer, packedLight, equippedProgress, swingProgress, hand);
            poseStack.popPose();
        }

        poseStack.pushPose();
        DocumentLayout.oneHandPage(poseStack, f, equippedProgress, swingProgress);
        renderItem(stack, buffer, poseStack, packedLight);
        poseStack.popPose();
    }

    public void renderItemInTwoHand(
            ItemInHandRenderer itemInHandRenderer,
            PoseStack poseStack,
            MultiBufferSource buffer,
            int packedLight,
            float equippedProgress,
            HumanoidArm hand,
            float swingProgress,
            ItemStack stack,
            float pitch
    ) {
        DocumentLayout.twoHandPose(poseStack, pitch, equippedProgress, swingProgress);
        if (!Minecraft.getInstance().player.isInvisible()) {
            poseStack.pushPose();
            poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
            renderArm(poseStack, buffer, packedLight, HumanoidArm.LEFT);
            renderArm(poseStack, buffer, packedLight, HumanoidArm.RIGHT);

            poseStack.popPose();
        }

        DocumentLayout.twoHandPage(poseStack, swingProgress);
        renderItem(stack, buffer, poseStack, packedLight);
    }

    public void renderArm(PoseStack poseStack, MultiBufferSource buffer, int packedLight, HumanoidArm side){
        AbstractClientPlayer player = Minecraft.getInstance().player;
        assert player != null;
        PlayerRenderer playerRenderer = (PlayerRenderer) Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(
                player
        );
        poseStack.pushPose();
        float f = side == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        poseStack.mulPose(Axis.YP.rotationDegrees(92.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(45.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(f * -41.0F));
        poseStack.translate(f * 0.3F, -1.1F, 0.45F);
        if (side == HumanoidArm.RIGHT) {
            playerRenderer.renderRightHand(poseStack, buffer, packedLight, player);
        } else {
            playerRenderer.renderLeftHand(poseStack, buffer, packedLight, player);
        }

        poseStack.popPose();
    }

    public void renderItem(ItemStack stack, MultiBufferSource bufferSource, PoseStack poseStack, int light) {
        if(!stack.getComponents().has(DOCUMENT_COMPONENT.getEntry()))
            return;
        Map<Holder<DocumentComponentType<?>>, Object> components =
                stack.getComponents().get(DOCUMENT_COMPONENT.getEntry());

        if(components == null)
            return;

        DocumentLayout.pageCoordinates(poseStack);

        this.renderBackground(stack, bufferSource, poseStack, light);

        for (Map.Entry<Holder<DocumentComponentType<?>>, Object> entries : components.entrySet()) {
            Holder<DocumentComponentType<?>> holder = entries.getKey();
            Object componentValue = entries.getValue();
            DocumentComponentRenderer<?> renderer = DocumentItemRenderer.getComponentRendererFor(holder.value());
            if(renderer == null)
                continue;
            castRender(renderer,componentValue, stack, bufferSource, poseStack, light);
        }

    }

    protected void renderBackground(ItemStack stack, MultiBufferSource bufferSource, PoseStack poseStack, int light) {

    }

    @SuppressWarnings("unchecked")
    private static <T> void castRender(
            DocumentComponentRenderer<T> renderer,
            Object componentValue,
            ItemStack stack,
            MultiBufferSource bufferSource,
            PoseStack poseStack,
            int light
    ) {
        renderer.render(
                (T) componentValue,
                stack,
                bufferSource,
                poseStack,
                light
        );
    }

    private static final HashMap<DocumentComponentType<?>, DocumentComponentRenderer<?>> COMPONENT_RENDERER_MAP = new HashMap<>();

    public static <T> void registerComponentRenderer(
            DocumentComponentType<T> type,
            DocumentComponentRenderer<T> renderer
    ) {
        COMPONENT_RENDERER_MAP.put(type, renderer);
    }

    @SuppressWarnings("unchecked")
    private static <T> DocumentComponentRenderer<T> getComponentRendererFor(DocumentComponentType<T> type) {
        return (DocumentComponentRenderer<T>) COMPONENT_RENDERER_MAP.get(type);
    }
}
