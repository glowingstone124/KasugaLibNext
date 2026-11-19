package lib.kasuga.inject;

/**
 * This class is a utility for environment detecting.
 */
public class Envs {

    /**
     * Dev means the environment in IDEs like IDEA, Eclipse and so on.
     * @return is we are in dev environment?
     */
    public static boolean isDevEnvironment() {
        return lib.kasuga.inject.class_loader.Envs.isDevEnvironment();
    }

    /**
     * Client means we are in the client game side. The client controls rendering or client ticks.
     * @return is the game a client?
     */
    public static boolean isClient() {
        return lib.kasuga.inject.class_loader.Envs.isClient();
    }

    /**
     * DedicatedServer is a kind of server that used for multiplayer gaming. These servers have no client,
     * they only runs the logical side of your world.
     * @return is the game a dedicated server?
     */
    public static boolean isDedicatedServer() {
        return lib.kasuga.inject.class_loader.Envs.isDedicatedServer();
    }
}