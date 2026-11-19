package lib.kasuga;

import io.micronaut.context.ApplicationContext;

import lib.kasuga.inject.ModApplicationContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;

@Mod(KasugaLib.MODID)
public class KasugaLib {

    public static final ApplicationContext context = ModApplicationContext.create(KasugaLib.class);
    public static final String MODID = "kasuga_lib";

    public KasugaLib(IEventBus modEventBus, ModContainer modContainer) {
        ModApplicationContext.init(context, modEventBus, modContainer);
        ModLoader.postEvent(new KasugaLibStartupEvent());
    }

    public static ApplicationContext getContext() {
        return context;
    }

    public static <T> T getBean(Class<T> beanType) {
        if(!context.isRunning())
            throw new IllegalStateException("Attempted to access KasugaLib bean before context was running");

        return context.getBean(beanType);
    }

    public static void afterRunning(IEventBus eventBus, Runnable hook) {
        if(!isRunning()) {
            eventBus.addListener(KasugaLibStartupEvent.class, (ev)->hook.run());
        } else {
            hook.run();
        }
    }

    public static boolean isRunning() {
        return context.isRunning();
    }
}
