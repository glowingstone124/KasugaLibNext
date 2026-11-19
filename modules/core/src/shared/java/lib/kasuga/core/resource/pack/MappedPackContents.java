package lib.kasuga.core.resource.pack;

import lib.kasuga.core.resource.identity.ResourcePaths;
import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.source.ResourceSourceDescriptor;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class MappedPackContents {
    private final CapturedScopedPackResources scoped;

    MappedPackContents(String name, List<ResourceRouting.Route> routes, Consumer<String> validatePath) {
        List<ResourceRouting.Route> entries = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (ResourceRouting.Route route : routes) {
            String path = route.targetPath();
            if (!path.equals("pack.mcmeta") && !path.equals("pack.png")
                    && !path.startsWith("assets/") && !path.startsWith("data/")) continue;
            validatePath.accept(path);
            if ((path.startsWith("assets/kasuga_generated/") || path.startsWith("data/kasuga_generated/"))
                    && route.file().descriptor().source().origin() != ResourceSourceDescriptor.Origin.DERIVED) {
                throw new IllegalArgumentException("Reserved generated resource namespace");
            }
            if (!paths.add(path)) {
                throw new IllegalArgumentException("ROUTING_CONFLICT: duplicate projected path: " + path);
            }
            entries.add(route);
        }
        scoped = new CapturedScopedPackResources(name, entries);
    }

    ScopedPackResources scoped() { return scoped; }

    @Nullable IoSupplier<InputStream> find(String path) {
        return scoped.isRegularFile("", path) ? () -> scoped.open("", path) : null;
    }

    void list(String type, String namespace, String prefix, BiConsumer<String, IoSupplier<InputStream>> output) {
        String root = type + "/" + namespace + "/";
        for (String path : scoped.paths()) {
            if (!path.startsWith(root)) continue;
            String relative = path.substring(root.length());
            if (ResourcePaths.contains(prefix, relative)) output.accept(relative, () -> scoped.open("", path));
        }
    }

    Set<String> namespaces(String directory) {
        String prefix = directory + "/";
        Set<String> result = new TreeSet<>();
        for (String path : scoped.paths()) {
            if (path.startsWith(prefix)) result.add(path.substring(prefix.length(), path.indexOf('/', prefix.length())));
        }
        return Set.copyOf(result);
    }

    void close() { scoped.close(); }
}
