package lib.kasuga.core.resource.pack;

import lib.kasuga.core.resource.identity.ResourcePaths;
import lib.kasuga.core.resource.routing.ResourceRouting;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One concrete source/root layer, never a flattened overlay. Every supplier opens captured bytes. */
public final class MappedPackResources extends AbstractPackMetadataResources implements PackResources {
    private final PackType type;
    private final MappedPackContents contents;

    public MappedPackResources(PackLocationInfo location, List<ResourceRouting.Route> routes) {
        this(location, routes, PackType.CLIENT_RESOURCES);
    }

    public MappedPackResources(PackLocationInfo location, List<ResourceRouting.Route> routes, PackType type) {
        super(location);
        this.type = type;
        this.contents = new MappedPackContents(location.id(), routes, MappedPackResources::validateMinecraftPath);
    }

    ScopedPackResources asScopedPackResources() { return contents.scoped(); }

    public static void validateMinecraftPath(String path) {
        ResourcePaths.path(path, false);
        if (path.equals("pack.mcmeta") || path.equals("pack.png")) return;
        String[] parts = path.split("/", 3);
        if (parts.length != 3 || !Set.of("assets", "data").contains(parts[0]) || Identifier.tryBuild(parts[1], parts[2]) == null)
            throw new IllegalArgumentException("Invalid projected Minecraft path: " + path);
    }
    @Override public @Nullable IoSupplier<InputStream> getRootResource(String... elements) {
        String path = ResourcePaths.path(String.join("/", elements), false);
        IoSupplier<InputStream> source = contents.find(path);
        if (source != null || !path.equals(PACK_META)) return source;
        return this::generatedMetadata;
    }

    @Override public @Nullable IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        return contents.find(type.getDirectory() + "/" + id.getNamespace() + "/" + id.getPath());
    }

    @Override public void listResources(PackType type, String namespace, String prefix, ResourceOutput output) {
        contents.list(type.getDirectory(), namespace, prefix,
                (path, input) -> output.accept(Identifier.fromNamespaceAndPath(namespace, path), input));
    }

    @Override public Set<String> getNamespaces(PackType type) {
        return contents.namespaces(type.getDirectory());
    }

    private InputStream generatedMetadata() {
        PackFormat format = SharedConstants.getCurrentVersion().packVersion(type);
        com.google.gson.JsonArray version = new com.google.gson.JsonArray();
        version.add(format.major());
        version.add(format.minor());
        com.google.gson.JsonObject pack = new com.google.gson.JsonObject();
        pack.addProperty("description", "Kasuga approved resources");
        pack.add("min_format", version);
        pack.add("max_format", version);
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("pack", pack);
        return new ByteArrayInputStream(root.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override public void close() { contents.close(); }
}
