package lib.kasuga.registration.minecraft.common;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.stream.Stream;

/** A reference retained by callers until the final registry key and holder are assigned. */
public class DeferredFillHolder<R> extends Holder.Reference<R> {
    private Holder<R> delegate;

    public DeferredFillHolder() {
        super(Type.STAND_ALONE, null, null, null);
    }

    public void setValue(Holder<R> value) {
        if (delegate != null) throw new IllegalStateException("Value already set");
        delegate = Objects.requireNonNull(value, "value");
    }

    private Holder<R> bound() {
        if (delegate == null) throw new IllegalStateException("Value not yet set");
        return delegate;
    }

    @Override public ResourceKey<R> key() { return bound().unwrapKey().orElseThrow(); }
    @Override public @Nullable ResourceKey<R> keyOrNull() { return delegate == null ? null : delegate.unwrapKey().orElse(null); }
    @Override public R value() { return bound().value(); }
    @Override public boolean isBound() { return bound().isBound(); }
    @Override public boolean is(TagKey<R> tag) { return bound().is(tag); }
    @Override public Stream<TagKey<R>> tags() { return bound().tags(); }
    @Override public boolean canSerializeIn(HolderOwner<R> owner) { return bound().canSerializeIn(owner); }
    @Override public boolean areComponentsBound() { return bound().areComponentsBound(); }
    @Override public DataComponentMap components() { return bound().components(); }
    @Override public Holder<R> getDelegate() { return bound().getDelegate(); }
    @Override public HolderLookup.@Nullable RegistryLookup<R> unwrapLookup() { return bound().unwrapLookup(); }

    @Override public int hashCode() {
        ResourceKey<R> key = keyOrNull();
        return key == null ? super.hashCode() : key.hashCode();
    }
}
