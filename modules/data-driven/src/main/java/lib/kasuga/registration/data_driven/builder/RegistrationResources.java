package lib.kasuga.registration.data_driven.builder;

import net.neoforged.neoforgespi.locating.IModFile;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class RegistrationResources {
    private RegistrationResources() {}

    static @Nullable RegistrationFile file(IModFile modFile, String... path) {
        Path candidate = modFile.findResource(path);
        return Files.isRegularFile(candidate) ? read(candidate) : null;
    }

    static @Nullable List<RegistrationFile> manifests(IModFile modFile, String modId) {
        Path directory = modFile.findResource(JsonTreeBuilder.indexDirectorySegments(modId));
        if (!Files.isDirectory(directory)) return null;
        List<RegistrationFile> files = new ArrayList<>();
        for (Path path : JsonTreeBuilder.listIndexFiles(directory, modId)) files.add(read(path));
        return files;
    }

    static boolean hasLegacyLayout(IModFile modFile, String modId) {
        return Files.isDirectory(modFile.findResource("data", modId, "kasugalib"));
    }

    private static RegistrationFile read(Path path) {
        return new RegistrationFile(path.toString(), () -> Files.newBufferedReader(path));
    }
}
