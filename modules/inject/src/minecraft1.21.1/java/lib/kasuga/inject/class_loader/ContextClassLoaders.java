package lib.kasuga.inject.class_loader;

import io.micronaut.context.ApplicationContext;

public final class ContextClassLoaders {
    private ContextClassLoaders() {}

    public static ClassLoader forMod(Class<?> mainClass) {
        if (!Envs.isDevEnvironment()) return null;
        return new CombinedClassLoader(mainClass.getClassLoader(), ApplicationContext.class.getClassLoader());
    }
}
