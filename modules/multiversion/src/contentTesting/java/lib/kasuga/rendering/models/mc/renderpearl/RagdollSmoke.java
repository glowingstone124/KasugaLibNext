package lib.kasuga.rendering.models.mc.renderpearl;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.dynamic.MinecraftModelRuntime;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftBlockPhysics;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftBlockRigidBody;
import lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollDeployments;
import lib.kasuga.rendering.models.mc.dynamic.physics.RagdollDeployment;
import lib.kasuga.rendering.models.uml.dynamic.physics.SkeletonRagdoll;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.io.IOException;

final class RagdollSmoke {
    private static final Identifier MODEL = id("models/smoke/mmd/ragdoll.pmx");
    private static final Identifier CONFIG = id("models/smoke/mmd/ragdoll.json");
    private static final Identifier INSTANCE = id("smoke/ragdoll");
    private static RagdollDeployment deployment;
    private static MinecraftBlockRigidBody prop;
    private static float startingHeight;
    private static double propHeight;
    private static int frames;
    private static boolean finished;

    private RagdollSmoke() {}

    static boolean needsFrames() {
        return !finished;
    }

    static void afterFrame(Minecraft client) throws IOException {
        if (deployment == null) {
            Camera camera = client.gameRenderer.mainCamera();
            Vec3 center = camera.position().add(new Vec3(camera.forwardVector()).scale(1.7));
            MinecraftRagdollDeployments.Request request = new MinecraftRagdollDeployments.Request(
                    MODEL, "", INSTANCE, CONFIG, new Transform().mul(camera.rotation()), true,
                    new Vector3d(center.x, center.y, center.z));
            deployment = MinecraftRagdollDeployments.deploy(request)
                    .orElseThrow(() -> new IllegalStateException("Could not deploy the Box3D smoke model"));
            if (!MinecraftModelRuntime.isRegistered(deployment.instance())
                    || deployment.ragdoll().bodies().size() != 1) {
                throw new IllegalStateException("Ragdoll is missing its scheduled body");
            }
            startingHeight = deployment.ragdoll().bodies().getFirst().position().y;
            Vec3 propCenter = center.add(new Vec3(camera.leftVector()).scale(1.3));
            prop = MinecraftBlockPhysics.spawn(client.level, propCenter, Blocks.STONE_SLAB.defaultBlockState())
                    .orElseThrow(() -> new IllegalStateException("Could not spawn the Box3D smoke block"));
            propHeight = prop.position().y;
            return;
        }
        SkeletonRagdoll.Body body = deployment.ragdoll().bodies().getFirst();
        float fall = startingHeight - body.position().y;
        if (fall < .07f && ++frames < 120) return;
        if (fall < .07f || !deployment.anchorTo(client.player) || !deployment.ragdoll().dragging()
                || !deployment.detachAnchor() || deployment.ragdoll().dragging()) {
            throw new IllegalStateException("Ragdoll did not fall and anchor through the native world runtime");
        }
        if (!deployment.remove() || MinecraftRagdollDeployments.get(INSTANCE).isPresent()
                || MinecraftModelRuntime.isRegistered(deployment.instance()) || !deployment.instance().isClosed()) {
            throw new IllegalStateException("Ragdoll deployment did not release its simulation and model");
        }
        if (propHeight - prop.position().y < .07 || !MinecraftBlockPhysics.remove(prop)
                || MinecraftBlockPhysics.activeCount() != 0) {
            throw new IllegalStateException("Physics block did not fall and release its native body");
        }
        finished = true;
        LogUtils.getLogger().info("KASUGA_RAGDOLL_PASS deployment,render-frame,gravity,anchor,remove displacement={}", fall);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(KasugaLib.MODID, path);
    }
}
