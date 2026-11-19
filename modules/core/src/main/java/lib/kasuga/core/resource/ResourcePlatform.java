package lib.kasuga.core.resource;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

final class ResourcePlatform {
    private ResourcePlatform() {}

    static void installClientHook(ResourceSystem system, IEventBus modBus, IEventBus gameBus) {
        modBus.addListener((FMLClientSetupEvent event) -> {
            ResourceManager manager = Minecraft.getInstance().getResourceManager();
            system.onClientReloaded(manager);
            installServerListener(system.getServerManager(null));
        });
    }

    static void installServerListener(ScopedResourceManager manager) {
        if (manager.getResourceManager() instanceof ReloadableResourceManager reloadable) {
            reloadable.registerReloadListenerIfNotPresent(manager);
        }
    }
}
