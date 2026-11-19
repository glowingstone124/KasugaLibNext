package lib.kasuga.inject.auto_configure;

import net.neoforged.fml.config.ModConfig;

final class ConfigurationTypes {
    static final ModConfig.Type COMMON = ModConfig.Type.LOCAL;
    static final ModConfig.Type SERVER = ModConfig.Type.SYNCED;

    private ConfigurationTypes() {}
}
