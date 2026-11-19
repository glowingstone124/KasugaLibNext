package lib.kasuga.inject.auto_configure;

import com.google.common.base.CaseFormat;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.inject.ArgumentInjectionPoint;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;

@Factory
@Context
public class SavedFactory {
    @Inject @Named("forgeEventBus")
    IEventBus forgeEventBus;

    @Bean
    public <T extends SavedData> Saved<T> createSaved(ArgumentInjectionPoint<?, ?> injectionPoint) {
        Class<?> dataClass = injectionPoint.asArgument().getTypeVariable("T").orElseThrow(() ->
                new IllegalArgumentException("Saved data injection requires a concrete SavedData type")).getType();
        String legacyName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, dataClass.getSimpleName());
        Saved.Impl<T> provider = new Saved.Impl<>(findType(dataClass), legacyName);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) provider.onLoad(server);
        forgeEventBus.addListener(provider::onServerStart);
        forgeEventBus.addListener(provider::onServerStop);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T extends SavedData> SavedDataType<T> findType(Class<?> dataClass) {
        String error = dataClass.getName() + " must declare public static SavedDataType<"
                + dataClass.getSimpleName() + "> TYPE";
        try {
            Field field = dataClass.getDeclaredField("TYPE");
            if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isPublic(field.getModifiers())
                    || !(field.getGenericType() instanceof ParameterizedType parameterized)
                    || parameterized.getRawType() != SavedDataType.class
                    || parameterized.getActualTypeArguments()[0] != dataClass
                    || !(field.get(null) instanceof SavedDataType<?> type)) {
                throw new IllegalArgumentException(error);
            }
            return (SavedDataType<T>) type;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException(error, exception);
        }
    }
}
