package lib.kasuga.core.resource.pack;

import lib.kasuga.core.resource.identity.ResourcePaths;
import lib.kasuga.core.resource.routing.ResourceRouting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One concrete source/root layer, never a flattened overlay. Every supplier opens captured bytes. */
public final class MappedPackResources implements PackResources {
    private final PackLocationInfo location;
    private final PackType type;
    private final MappedPackContents contents;

    public MappedPackResources(PackLocationInfo location, List<ResourceRouting.Route> routes) {
        this(location, routes, PackType.CLIENT_RESOURCES);
    }

    public MappedPackResources(PackLocationInfo location, List<ResourceRouting.Route> routes, PackType type) {
        this.location = location;
        this.type = type;
        this.contents = new MappedPackContents(location.id(), routes, MappedPackResources::validateMinecraftPath);
    }

    ScopedPackResources asScopedPackResources() { return contents.scoped(); }

    public static void validateMinecraftPath(String path) {
        ResourcePaths.path(path, false);
        if (path.equals("pack.mcmeta") || path.equals("pack.png")) return;
        String[] parts = path.split("/", 3);
        if (parts.length != 3 || !Set.of("assets", "data").contains(parts[0]) || ResourceLocation.tryBuild(parts[1], parts[2]) == null)
            throw new IllegalArgumentException("Invalid projected Minecraft path: " + path);
    }
    @Override public @Nullable IoSupplier<InputStream> getRootResource(String... elements) {
        return contents.find(ResourcePaths.path(String.join("/", elements), false));
    }

    @Override public @Nullable IoSupplier<InputStream> getResource(PackType type, ResourceLocation id) {
        return contents.find(type.getDirectory() + "/" + id.getNamespace() + "/" + id.getPath());
    }

    @Override public void listResources(PackType type, String namespace, String prefix, ResourceOutput output) {
        contents.list(type.getDirectory(), namespace, prefix,
                (path, input) -> output.accept(ResourceLocation.fromNamespaceAndPath(namespace, path), input));
    }

    @Override public Set<String> getNamespaces(PackType type) {
        return contents.namespaces(type.getDirectory());
    }

    @Override public <T> @Nullable T getMetadataSection(MetadataSectionSerializer<T> serializer) throws IOException {
        var source = contents.find("pack.mcmeta");
        if (source == null) {
            // Host-generated metadata, not a rewritten author file. MC 1.21.1 formats.
            var root = new com.google.gson.JsonObject();
            root.addProperty("pack_format", net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(type));
            root.addProperty("description", "Kasuga approved resources");
            if (serializer.getMetadataSectionName().equals("pack")) return serializer.fromJson(root);
            return null;
        }
        try (var input = source.get(); var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            var json = GsonHelper.parse(reader);
            return json.has(serializer.getMetadataSectionName()) ? serializer.fromJson(json.getAsJsonObject(serializer.getMetadataSectionName())) : null;
        }
    }
    @Override public PackLocationInfo location() { return location; }
    @Override public void close() { contents.close(); }
}
