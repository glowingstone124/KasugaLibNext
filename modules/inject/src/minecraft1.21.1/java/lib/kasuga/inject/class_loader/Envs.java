package lib.kasuga.inject.class_loader;

import net.neoforged.fml.loading.FMLLoader;

/**
 * This class is a utility for environment detecting.
 */
public class Envs {

    /**
     * Dev means the environment in IDEs like IDEA, Eclipse and so on.
     * @return is we are in dev environment?
     */
    public static boolean isDevEnvironment() {
        return !FMLLoader.isProduction();
    }

    /**
     * Client means we are in the client game side. The client controls rendering or client ticks.
     * @return is the game a client?
     */
    public static boolean isClient() {
        var dist = FMLLoader.getDist();
        return dist != null && dist.isClient();
    }

    /**
     * DedicatedServer is a kind of server that used for multiplayer gaming. These servers have no client,
     * they only runs the logical side of your world.
     * @return is the game a dedicated server?
     */
    public static boolean isDedicatedServer() {
        var dist = FMLLoader.getDist();
        return dist != null && dist.isDedicatedServer();
    }
}