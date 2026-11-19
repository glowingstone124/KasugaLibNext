package lib.kasuga.inject;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.BeanResolutionTraceMode;
import io.micronaut.inject.qualifiers.Qualifiers;
import lib.kasuga.inject.auto_configure.Configurable;
import lib.kasuga.inject.class_loader.ContextClassLoaders;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;

public class ModApplicationContext {
    public static ApplicationContext create(Class<?> modMainClass) {
        return create(modMainClass, (String[]) null);
    }

    public static ApplicationContext create(Class<?> modMainClass, String... packages) {
        ApplicationContextBuilder builder = ApplicationContext.builder().mainClass(modMainClass)
                .beanResolutionTrace(BeanResolutionTraceMode.STANDARD_OUT);
        ClassLoader classLoader = ContextClassLoaders.forMod(modMainClass);
        if (classLoader != null) builder.classLoader(classLoader);
        if (packages != null && packages.length > 0) builder.packages(packages);
        else if (classLoader == null) builder.packages();
        return builder.build();
    }

    public static void init(ApplicationContext context, IEventBus modEventBus, ModContainer modContainer) {
        context.registerSingleton(IEventBus.class, modEventBus, Qualifiers.byName("modEventBus"));
        context.registerSingleton(IEventBus.class, NeoForge.EVENT_BUS, Qualifiers.byName("forgeEventBus"));
        context.registerSingleton(ModContainer.class, modContainer, Qualifiers.byName("modContainer"));
        context.start();
        Configurable.configure(context, modEventBus, modContainer);
    }

}
