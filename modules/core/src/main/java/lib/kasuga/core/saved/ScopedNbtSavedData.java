package lib.kasuga.core.saved;

import com.mojang.logging.LogUtils;
import com.mojang.datafixers.DataFixer;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ScopedNbtSavedData<T extends SavedData> extends CustomSavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Path path;
    private final SavedData.Factory<T> factory;

    DimensionDataStorage dimensionDataStorage;
    private Set<String> pendingRemove = new HashSet<>();

    public ScopedNbtSavedData(
            String basePath,
            SavedData.Factory<T> factory,
            Path path,
            HolderLookup.Provider provider,
            DataFixer fixer
    ) {
        this.path = path.resolve(basePath);
        if(!this.path.toFile().exists()) {
            if(!this.path.toFile().mkdirs())
                throw new IllegalStateException("Cannot mkdir for NBT Saved Data: " + basePath);
        }
        this.factory = factory;
        this.dimensionDataStorage = new DimensionDataStorage(this.path.toFile(), fixer, provider);
    }
    @Override
    public void save(Path path, HolderLookup.Provider provider) {
        if (!pendingRemove.isEmpty()) IOUtilities.waitUntilIOWorkerComplete();
        Iterator<String> keys = pendingRemove.iterator();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                Files.deleteIfExists(this.path.resolve(key + ".dat"));
                Files.deleteIfExists(this.path.resolve(key + ".nbt"));
                keys.remove();
            } catch (IOException failure) {
                LOGGER.warn("Could not remove scoped saved data {}", this.path.resolve(key), failure);
            }
        }

        this.dimensionDataStorage.save();
    }

    public T get(String key) {
        if(this.pendingRemove.contains(key))
            return null;
        return this.dimensionDataStorage.get(this.factory, key);
    }

    public T computeIfAbsent(String key) {
        if(this.pendingRemove.contains(key)) {
            this.pendingRemove.remove(key);
            T data = this.factory.constructor().get();
            this.dimensionDataStorage.set(key, data);
            return data;
        }
        return this.dimensionDataStorage.computeIfAbsent(this.factory, key);
    }

    public List<String> getAllSavedNames() {
        File[] files = this.path.toFile().listFiles();
        if(files == null) return List.of();
        Set<String> keys = new LinkedHashSet<>();
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && (name.endsWith(".dat") || name.endsWith(".nbt"))) {
                keys.add(name.substring(0, name.length() - 4));
            }
        }
        return List.copyOf(keys);
    }

    public void remove(String data) {
        Path target = this.path.resolve(data + ".dat").normalize();
        if (!target.startsWith(this.path.normalize())) {
            throw new IllegalArgumentException("Saved data path escapes its scope: " + data);
        }
        //noinspection DataFlowIssue
        this.dimensionDataStorage.set(data, null);
        this.pendingRemove.add(data);
    }
}
