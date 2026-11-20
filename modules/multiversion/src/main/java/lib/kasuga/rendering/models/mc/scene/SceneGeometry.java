package lib.kasuga.rendering.models.mc.scene;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.KasugaLib;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** Keeps extracted scene geometry with the level frame that will submit it. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class SceneGeometry {
    private static final ContextKey<List<Draw>> DRAWS =
            new ContextKey<>(Identifier.fromNamespaceAndPath(KasugaLib.MODID, "scene_geometry"));

    private SceneGeometry() {}

    public static void add(ExtractLevelRenderStateEvent event, PoseStack pose, RenderType layer,
                           SubmitNodeCollector.CustomGeometryRenderer geometry) {
        LevelRenderState state = event.getRenderState();
        List<Draw> draws = state.getRenderData(DRAWS);
        if (draws == null) {
            draws = new ArrayList<>();
            state.setRenderData(DRAWS, draws);
        }
        draws.add(new Draw(new Matrix4f(pose.last().pose()), layer, geometry));
    }

    @SubscribeEvent
    public static void submit(SubmitCustomGeometryEvent event) {
        List<Draw> draws = event.getLevelRenderState().getRenderData(DRAWS);
        if (draws == null) return;
        PoseStack pose = event.getPoseStack();
        for (Draw draw : draws) {
            pose.pushPose();
            try {
                pose.mulPose(draw.transform());
                event.getSubmitNodeCollector().submitCustomGeometry(pose, draw.layer(), draw.geometry());
            } finally {
                pose.popPose();
            }
        }
    }

    private record Draw(Matrix4f transform, RenderType layer, SubmitNodeCollector.CustomGeometryRenderer geometry) {}
}
