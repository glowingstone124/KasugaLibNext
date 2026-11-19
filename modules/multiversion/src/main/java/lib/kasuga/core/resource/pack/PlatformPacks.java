package lib.kasuga.core.resource.pack;

import lib.kasuga.core.resource.routing.ResourceRouting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackMetadataResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class PlatformPacks {
    private PlatformPacks() {}

    static @Nullable Pack create(String id, List<ResourceRouting.Route> routes, PackType type) {
        PackLocationInfo location = new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty());
        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override public PackMetadataResources openMetadata(PackLocationInfo info) {
                return new MappedPackResources(info, routes, type);
            }

            @Override public Stream<PackResources> openResources(PackLocationInfo info, Pack.Metadata metadata) {
                return Stream.of(new MappedPackResources(info, routes, type));
            }
        };
        return Pack.readMetaAndCreate(location, supplier, type, new PackSelectionConfig(true, Pack.Position.TOP, true));
    }

    static void publish(PackType type, List<Pack> ordered, Consumer<Pack> output) {
        if (ordered.isEmpty()) return;

        // NeoForge sorts repository entries by ID. Children retain their declared layer order.
        Pack root = create("kasuga/approved", List.of(), type);
        if (root == null) throw new IllegalStateException("Invalid approved pack group metadata");
        output.accept(root.withChildren(ordered));
    }
}
