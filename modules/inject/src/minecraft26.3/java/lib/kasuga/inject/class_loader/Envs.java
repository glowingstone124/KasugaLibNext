package lib.kasuga.inject.class_loader;

import net.neoforged.fml.loading.FMLLoader;

public final class Envs {
    private Envs() {}

    public static boolean isDevEnvironment() {
        FMLLoader loader = FMLLoader.getCurrentOrNull();
        return loader != null && !loader.isProduction();
    }

    public static boolean isClient() {
        FMLLoader loader = FMLLoader.getCurrentOrNull();
        return loader != null && loader.getDist().isClient();
    }

    public static boolean isDedicatedServer() {
        FMLLoader loader = FMLLoader.getCurrentOrNull();
        return loader != null && loader.getDist().isDedicatedServer();
    }
}
