package lib.kasuga.inject.class_loader;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.loading.FMLLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Provides paths to mod resources for consumers that require NIO access. */
public final class ForgePathFinder implements IPathFinder, AutoCloseable {
    private final JarContents contents;
    private final Map<Path, FileSystem> archives = new HashMap<>();
    private boolean closed;

    public ForgePathFinder(ModContainer modContainer) {
        contents = Objects.requireNonNull(modContainer, "modContainer")
                .getModInfo().getOwningFile().getFile().getContents();
        FMLLoader.getCurrent().addCloseCallback(this);
    }

    @Override
    public synchronized Path find(String path) {
        if (closed) throw new IllegalStateException("Mod resource paths are closed");
        URI resource = contents.findFile(path).orElse(null);
        if (resource == null) return null;
        if (!"jar".equals(resource.getScheme())) return Path.of(resource);
        try {
            JarURLConnection connection = (JarURLConnection) resource.toURL().openConnection();
            Path archive = Path.of(connection.getJarFileURL().toURI());
            FileSystem fileSystem = archives.get(archive);
            if (fileSystem == null) {
                fileSystem = FileSystems.newFileSystem(archive);
                archives.put(archive, fileSystem);
            }
            return fileSystem.getPath("/").resolve(connection.getEntryName());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid mod resource URI: " + resource, exception);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not open mod resource: " + resource, exception);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (FileSystem archive : archives.values()) {
            try {
                archive.close();
            } catch (IOException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        archives.clear();
        if (failure != null) throw failure;
    }
}
