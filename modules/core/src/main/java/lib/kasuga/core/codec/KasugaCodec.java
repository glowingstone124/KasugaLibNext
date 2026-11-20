package lib.kasuga.core.codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;

public class KasugaCodec {
    public static <B extends ByteBuf, K, V, M extends Map<K, ? extends V>> StreamCodec<B, M> mapFunc(
            final IntFunction<? extends M> factory,
            final StreamCodec<? super B, K> keyCodec,
            final Function<K, StreamCodec<? super B, ? extends V>> valueCodecFunction,
            final int maxSize,
            final boolean cacheValueCodecByKey
    ) {
        HashMap<K, StreamCodec<? super B, ? extends V>> valueCodecCache = new HashMap<>();
        return new StreamCodec<B, M>() {
            public void encode(@NotNull B buf, @NotNull M map) {
                ByteBufCodecs.writeCount(buf, map.size(), maxSize);
                map.forEach((key, value) -> {
                    keyCodec.encode(buf, key);
                    if(cacheValueCodecByKey) {
                        ((StreamCodec<B,V>) valueCodecCache.computeIfAbsent(key, valueCodecFunction))
                                .encode(buf, value);
                    } else {
                        ((StreamCodec<B,V>) valueCodecFunction.apply(key))
                                .encode(buf, value);
                    }
                });
            }

            public void putSafe(@NotNull M map, @NotNull K key, @NotNull V value) {
                ((Map<K,V>) map).put(key, value);
            }

            public @NotNull M decode(@NotNull B buf) {
                int i = ByteBufCodecs.readCount(buf, maxSize);
                M m = factory.apply(Math.min(i, 65536));

                for(int j = 0; j < i; ++j) {
                    K k = keyCodec.decode(buf);
                    V v;
                    if(cacheValueCodecByKey) {
                        v = valueCodecCache.computeIfAbsent(k, valueCodecFunction)
                                .decode(buf);
                    } else {
                        v = valueCodecFunction.apply(k)
                                .decode(buf);
                    }
                    putSafe(m, k, v);
                }

                return m;
            }
        };
    }
}
