package lib.kasuga.inject.class_loader;

import io.micronaut.context.ApplicationContext;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.language.IModFileInfo;

import java.io.IOException;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipFile;

public final class ContextClassLoaders {
    private static final String MICRONAUT_METADATA = "META-INF/micronaut/";

    private ContextClassLoaders() {}

    public static ClassLoader forMod(Class<?> mainClass) {
        FMLLoader loader = FMLLoader.getCurrentOrNull();
        if (loader == null) return null;
        return new CombinedClassLoader(mainClass.getClassLoader(), ApplicationContext.class.getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                Enumeration<URL> resources = super.getResources(name);
                if (!MICRONAUT_METADATA.equals(name)) return resources;

                // FML 12 resolves individual resources, while Micronaut enumerates this directory.
                Set<URL> metadata = new LinkedHashSet<>();
                while (resources.hasMoreElements()) metadata.add(resources.nextElement());
                for (Path root : contentRoots(loader)) {
                    URL location = metadataLocation(root);
                    if (location != null) metadata.add(location);
                }
                return Collections.enumeration(metadata);
            }
        };
    }

    private static Set<Path> contentRoots(FMLLoader loader) {
        Set<Path> roots = new LinkedHashSet<>();
        for (IModFileInfo info : ModList.get().getModFiles()) {
            roots.addAll(info.getFile().getContents().getContentRoots());
        }
        for (ResolvedModule module : loader.getGameLayer().configuration().modules()) {
            URI location = module.reference().location().orElse(null);
            if (location != null && "file".equals(location.getScheme())) roots.add(Path.of(location));
        }
        return roots;
    }

    private static URL metadataLocation(Path root) throws IOException {
        if (Files.isDirectory(root)) {
            Path directory = root.resolve(MICRONAUT_METADATA);
            return Files.isDirectory(directory) ? directory.toUri().toURL() : null;
        }
        if (!Files.isRegularFile(root)) return null;
        try (ZipFile archive = new ZipFile(root.toFile())) {
            if (archive.stream().noneMatch(entry -> entry.getName().startsWith(MICRONAUT_METADATA))) return null;
        }
        return URI.create("jar:" + root.toUri().toASCIIString() + "!/" + MICRONAUT_METADATA).toURL();
    }
}
