package lib.kasuga.scene;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import org.joml.Vector3f;

@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
abstract class ScenePropPlatform {
    static boolean screenOpen(Minecraft client) { return client.gui.screen() != null; }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = event.getLevel();
        Vec3 camera = event.getCamera().position();
        float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        SceneProps.visitVisible(level, prop -> {
            Vec3 position = prop.position();
            Vector3f scale = prop.renderScale();
            PoseStack pose = new PoseStack();
            pose.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
            pose.rotate(prop.renderRotation());
            pose.scale(scale.x, scale.y, scale.z);
            BlockPos blockPosition = BlockPos.containing(position);
            int light = LightCoordsUtil.getLightCoords(level, blockPosition);
            MinecraftModelSource.Context context = new MinecraftModelSource.Context(pose, event, partialTick, light);
            if (prop.block() != null) {
                pose.translate(-.5, -.5, -.5);
                context.submitMovingBlock(level, prop.block(), blockPosition, prop.lightAnchor());
                return;
            }
            ItemStackRenderState item = new ItemStackRenderState();
            client.getItemModelResolver().updateForTopItem(item, prop.item(), ItemDisplayContext.FIXED, level, null, 0);
            context.submit((stack, nodes) -> item.submit(stack, nodes, light, OverlayTexture.NO_OVERLAY, 0));
        });
    }
}
