package lib.kasuga.inject.auto_configure;

import io.micronaut.context.ApplicationContext;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public interface Configurable {
    public static void configure(ApplicationContext context, IEventBus modEventBus, ModContainer modContainer) {
        ModConfigSpec.Builder specClient = new ModConfigSpec.Builder();
        ModConfigSpec.Builder specServer = new ModConfigSpec.Builder();
        ModConfigSpec.Builder specCommon = new ModConfigSpec.Builder();
        ModConfigSpec.Builder specStartUp = new ModConfigSpec.Builder();
        for (Configurable configurable : context.getBeansOfType(Configurable.class)) {
            configurable.onConfigure(ModConfig.Type.CLIENT, specClient);
            configurable.onConfigure(ConfigurationTypes.SERVER, specServer);
            configurable.onConfigure(ConfigurationTypes.COMMON, specCommon);
            configurable.onConfigure(ModConfig.Type.STARTUP, specStartUp);
        }
        registerConfig(modContainer, ModConfig.Type.CLIENT, specClient);
        registerConfig(modContainer, ConfigurationTypes.SERVER, specServer);
        registerConfig(modContainer, ConfigurationTypes.COMMON, specCommon);
        registerConfig(modContainer, ModConfig.Type.STARTUP, specStartUp);
    }


    private static void registerConfig(ModContainer modContainer, ModConfig.Type type, ModConfigSpec.Builder specBuilder) {
        IConfigSpec spec = specBuilder.build();
        if (spec.isEmpty()) return;
        String suffix = type == ConfigurationTypes.COMMON ? "common"
                : type == ConfigurationTypes.SERVER ? "server"
                : type.name().toLowerCase(java.util.Locale.ROOT);
        modContainer.registerConfig(type, spec, modContainer.getModId() + "-" + suffix + ".toml");
    }

    public default void onConfigure(ModConfig.Type type, ModConfigSpec.Builder builder) {
        if(type == ModConfig.Type.CLIENT)
            configureClient(builder);
        else if(type == ConfigurationTypes.SERVER)
            configureServer(builder);
        else if(type == ConfigurationTypes.COMMON)
            configureCommon(builder);
        else if(type == ModConfig.Type.STARTUP)
            configureStartup(builder);
    }

    public default void configureClient(ModConfigSpec.Builder builder){}
    public default void configureServer(ModConfigSpec.Builder builder){}
    public default void configureCommon(ModConfigSpec.Builder builder){}
    public default void configureStartup(ModConfigSpec.Builder builder){}
}
