package lib.kasuga.registration.data_driven.builder;

import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.neoforgespi.locating.IModFile;
import org.jetbrains.annotations.Nullable;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

final class RegistrationResources {
    private RegistrationResources() {}

    static @Nullable RegistrationFile file(IModFile modFile, String... path) {
        String name = String.join("/", path);
        JarContents contents = modFile.getContents();
        return contents.containsFile(name) ? read(contents, name) : null;
    }

    static @Nullable List<RegistrationFile> manifests(IModFile modFile, String modId) {
        JarContents contents = modFile.getContents();
        String directory = String.join("/", JsonTreeBuilder.indexDirectorySegments(modId));
        String prefix = directory + "/";
        NavigableSet<String> paths = new TreeSet<>();
        contents.visitContent(prefix, (path, resource) -> {
            if (!path.startsWith(prefix) || path.length() == prefix.length()) return;
            paths.add(path.substring(prefix.length()));
        });
        if (paths.isEmpty() && !contents.containsFile(directory)) return null;
        List<RegistrationFile> files = new ArrayList<>();
        for (String path : paths) {
            if (path.indexOf('/') == -1 && path.endsWith(".json")) files.add(read(contents, prefix + path));
        }
        return files;
    }

    static boolean hasLegacyLayout(IModFile modFile, String modId) {
        JarContents contents = modFile.getContents();
        String directory = "data/" + modId + "/kasugalib";
        if (contents.containsFile(directory)) return true;
        List<String> entries = new ArrayList<>();
        contents.visitContent(directory + "/", (path, resource) -> entries.add(path));
        return !entries.isEmpty();
    }

    private static RegistrationFile read(JarContents contents, String path) {
        return new RegistrationFile(path, () -> new InputStreamReader(contents.openFile(path), StandardCharsets.UTF_8));
    }
}
