package lib.kasuga;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import lib.kasuga.inject.class_loader.Envs;
import lib.kasuga.registration.Registry;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmBlockEntityFactories;
import net.neoforged.bus.api.IEventBus;

@Context
public final class KasugaLibApplication {
    public static final Registry REGISTRY = KasugaLibRegistry.getRegistryOf(KasugaLib.MODID);

    private final IEventBus modEventBus;

    public KasugaLibApplication(@Named("modEventBus") IEventBus modEventBus) {
        this.modEventBus = modEventBus;
    }

    @PostConstruct public void init() {
        FsmBlockEntityFactories.registerBuiltin();
        REGISTRY.register(modEventBus);
        if (!Envs.isDedicatedServer()) REGISTRY.registerClient(modEventBus);
    }
}
