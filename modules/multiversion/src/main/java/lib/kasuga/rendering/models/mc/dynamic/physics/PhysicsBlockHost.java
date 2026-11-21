package lib.kasuga.rendering.models.mc.dynamic.physics;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.joml.Quaternionf;

/** Submits simulated blocks through Minecraft's native moving-block feature. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
abstract class PhysicsBlockHost {
    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(PhysicsCommands.physicsCommand());
        event.getDispatcher().register(PhysicsCommands.ragdollCommand());
    }

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        ClientLevel level = event.getLevel();
        Vec3 camera = event.getCamera().position();
        float partialTick = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (MinecraftBlockRigidBody block : MinecraftBlockPhysics.snapshot()) {
            Vec3 center = block.position();
            Quaternionf rotation = block.body().rotationRef();

            PoseStack pose = new PoseStack();
            pose.translate(center.x - camera.x, center.y - camera.y, center.z - camera.z);
            pose.rotate(rotation);
            pose.translate(-MinecraftBlockRigidBody.HALF_EXTENT, -MinecraftBlockRigidBody.HALF_EXTENT,
                    -MinecraftBlockRigidBody.HALF_EXTENT);
            MinecraftModelSource.Context context = new MinecraftModelSource.Context(pose, event, partialTick, 0);
            context.submitMovingBlock(level, block.state(), block.blockPosition(), block.lightAnchor());
        }
    }
}
