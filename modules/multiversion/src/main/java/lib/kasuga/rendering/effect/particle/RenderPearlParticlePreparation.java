package lib.kasuga.rendering.effect.particle;

import lib.kasuga.KasugaLib;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.PrepareRenderBuffersEvent;
import org.jetbrains.annotations.ApiStatus;

@ApiStatus.Internal
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class RenderPearlParticlePreparation {
    private RenderPearlParticlePreparation() {}

    @SubscribeEvent
    public static void prepare(PrepareRenderBuffersEvent event) {
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        ClientParticleRuntime.prepare(camera.pos, camera.viewRotationMatrix);
    }
}
