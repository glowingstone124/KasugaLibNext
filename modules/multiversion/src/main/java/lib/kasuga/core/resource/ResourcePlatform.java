package lib.kasuga.core.resource;

import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent;

final class ResourcePlatform {
    private ResourcePlatform() {}

    static void installClientHook(ResourceSystem system, IEventBus modBus, IEventBus gameBus) {
        if (FMLEnvironment.getDist().isClient()) Client.install(system, gameBus);
    }

    static void installServerListener(ScopedResourceManager manager) {
        // The server publishes its rebuilt manager after a successful reload.
    }

    private static final class Client {
        private static void install(ResourceSystem system, IEventBus gameBus) {
            gameBus.addListener((ClientResourceLoadFinishedEvent event) -> {
                system.onClientReloaded(Minecraft.getInstance().getResourceManager());
                gameBus.post(new ClientResourceReloadFinishEvent());
            });
        }
    }
}
