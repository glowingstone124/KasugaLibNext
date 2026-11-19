package lib.kasuga.registration.core;

import net.minecraft.Util;

import java.util.function.BiFunction;
import java.util.function.Function;

public class ModifierType<T> {
    public final boolean skipSelf;


    public ModifierType() {
        this(false);
    }

    public ModifierType(boolean skipSelf) {
        this.skipSelf = skipSelf;
    }

    public static <T, R> Function<T, R> memoize(Function<T, R> factory) {
        return Util.memoize(factory);
    }

    public static <T, U, R> BiFunction<T, U, R> memoize(BiFunction<T, U, R> factory) {
        return Util.memoize(factory);
    }
}
