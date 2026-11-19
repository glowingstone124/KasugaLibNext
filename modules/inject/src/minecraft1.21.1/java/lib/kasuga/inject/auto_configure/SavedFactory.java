package lib.kasuga.inject.auto_configure;

import com.google.common.base.CaseFormat;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.inject.ArgumentInjectionPoint;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.IEventBus;

import java.lang.reflect.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;

@Factory
@Context
public class SavedFactory {
    @Inject() @Named("forgeEventBus")
    IEventBus forgeEventBus;
    @Bean
    public <T extends SavedData> Saved<T> createSaved(ArgumentInjectionPoint<?, ?> ip) {
        //noinspection unchecked
        Class<Saved<T>> clazz = (Class<Saved<T>>) ip.asArgument().getTypeVariable("T").get().getType();

        Supplier<T> supplier = null;
        BiFunction<CompoundTag, HolderLookup.Provider, T> loadFunction = null;

        for (Constructor<?> constructor : clazz.getConstructors()) {
            Parameter[] parameters = constructor.getParameters();
            if(parameters.length == 0) supplier = ()->{
                try {
                    //noinspection unchecked
                    return (T) constructor.newInstance();
                } catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
                    throw new RuntimeException(e);
                }
            };

            if(parameters.length == 1) {
                if(parameters[0].getType() == CompoundTag.class) {
                    loadFunction = (nbt, holderLookup) -> {
                        try {
                            //noinspection unchecked
                            return (T) constructor.newInstance(nbt);
                        } catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
                            throw new RuntimeException(e);
                        }
                    };
                }
            }

            if(parameters.length == 2) {
                if(parameters[0].getType() == CompoundTag.class && parameters[1].getType() == HolderLookup.Provider.class) {
                    loadFunction = (nbt, holderLookup) -> {
                        try {
                            //noinspection unchecked
                            return (T) constructor.newInstance(nbt, holderLookup);
                        } catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
                            throw new RuntimeException(e);
                        }
                    };
                } else if(parameters[0].getType() == HolderLookup.Provider.class && parameters[1].getType() == CompoundTag.class) {
                    loadFunction = (nbt, holderLookup) -> {
                        try {
                            //noinspection unchecked
                            return (T) constructor.newInstance(holderLookup, nbt);
                        } catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
                            throw new RuntimeException(e);
                        }
                    };
                }
            }
        }

        if(loadFunction == null) {
            for (Method method : clazz.getMethods()) {
                if(!Modifier.isStatic(method.getModifiers()))
                    continue;
                if(method.getParameterCount() == 2 &&
                   method.getParameterTypes()[0] == CompoundTag.class &&
                   method.getParameterTypes()[1] == HolderLookup.Provider.class &&
                   method.getReturnType() == clazz) {
                    loadFunction = (nbt, holderLookup) -> {
                        try {
                            //noinspection unchecked
                            return (T) method.invoke(null, nbt, holderLookup);
                        } catch (IllegalAccessException | InvocationTargetException e) {
                            throw new RuntimeException(e);
                        }
                    };
                }
            }
        }

        if(supplier == null) throw new RuntimeException("No valid supplier constructor found for " + clazz.getName());
        if(loadFunction == null) throw new RuntimeException("No valid loadFunction constructor found for " + clazz.getName());

        String nameFromClazz = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, clazz.getSimpleName());

        SavedData.Factory<T> factory = new SavedData.Factory<T>(supplier, loadFunction);
        Saved.Impl<T> impl = new Saved.Impl<T>(nameFromClazz, factory);
        forgeEventBus.addListener(impl::onServerStart);
        forgeEventBus.addListener(impl::onServerStop);
        return impl;
    }
}
