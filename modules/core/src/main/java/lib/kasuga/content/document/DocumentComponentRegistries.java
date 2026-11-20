package lib.kasuga.content.document;

import com.mojang.serialization.Codec;
import io.micronaut.context.annotation.Context;
import lib.kasuga.KasugaLib;
import lib.kasuga.KasugaLibApplication;
import lib.kasuga.core.codec.KasugaCodec;
import lib.kasuga.registration.minecraft.data_component.DataComponentReg;
import lib.kasuga.registration.minecraft.registry.RegistryReg;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegistryBuilder;

import java.util.HashMap;
import java.util.Map;

@Context()
public class DocumentComponentRegistries {
    public static ResourceKey<Registry<DocumentComponentType<?>>> DOCUMENT_COMPONENT_KEY = ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "document_component_type"));
    public static Registry<DocumentComponentType<?>> DOCUMENT_COMPONENT_REGISTRY = new RegistryBuilder<>(DOCUMENT_COMPONENT_KEY)
            .sync(true)
            .create();

    public static final Codec<Map<Holder<DocumentComponentType<?>>, Object>> DOCUMENT_COMPONENT_PERSISTENT = Codec.dispatchedMap(
            DOCUMENT_COMPONENT_REGISTRY.holderByNameCodec(),
            s->s.value().codec()
    );

    public static final StreamCodec<? super RegistryFriendlyByteBuf, Map<Holder<DocumentComponentType<?>>, Object>>
            DOCUMENT_COMPONENT_BYTE_BUF = KasugaCodec.mapFunc(
                    HashMap::new,
                    ByteBufCodecs.holderRegistry(DOCUMENT_COMPONENT_KEY),
                    (Holder<DocumentComponentType<?>> h)->h.value().networkCodec(),
                    Integer.MAX_VALUE,
                    true);

    public static final RegistryReg<DocumentComponentType<?>> DOCUMENT_COMPONENT_REGISTRY_REG = new RegistryReg<>(DOCUMENT_COMPONENT_REGISTRY)
            .setParent(KasugaLibApplication.REGISTRY);

    public static DataComponentReg<Map<Holder<DocumentComponentType<?>>, Object>> DOCUMENT_COMPONENT = new DataComponentReg<Map<Holder<DocumentComponentType<?>>, Object>>("document_components", builder->builder.networkSynchronized(DocumentComponentRegistries.DOCUMENT_COMPONENT_BYTE_BUF)
            .persistent(DocumentComponentRegistries.DOCUMENT_COMPONENT_PERSISTENT))
            .setParent(KasugaLibApplication.REGISTRY);
}
