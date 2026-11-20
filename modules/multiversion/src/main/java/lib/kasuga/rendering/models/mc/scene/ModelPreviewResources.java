package lib.kasuga.rendering.models.mc.scene;

import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.ClientResourceReloadFinishEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/** Tracks the resource generation used by models and the native block atlas. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ModelPreviewResources {
    private static long generation;

    private ModelPreviewResources() {}

    public static long generation() { return generation; }

    @SubscribeEvent
    public static void reloaded(ClientResourceReloadFinishEvent event) { generation++; }
}
