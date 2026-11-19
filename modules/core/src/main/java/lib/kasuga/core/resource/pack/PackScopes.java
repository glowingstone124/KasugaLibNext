package lib.kasuga.core.resource.pack;

import lib.kasuga.mixins.CompositePackResourcesAccess;
import lib.kasuga.mixins.FilePackResourcesMixin;
import lib.kasuga.mixins.PathPackResourcesMixin;
import lib.kasuga.mixins.SharedZipFileAccessAccess;
import net.minecraft.server.packs.CompositePackResources;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

public final class PackScopes {
    private PackScopes() {}

    public static List<ScopedPackResources> adapt(ResourceManager manager) {
        List<ScopedPackResources> result = new ArrayList<>();
        try (Stream<PackResources> packs = manager.listPacks()) {
            Iterator<PackResources> iterator = packs.iterator();
            while (iterator.hasNext()) add(iterator.next(), result);
        }
        return List.copyOf(result);
    }

    private static void add(PackResources pack, List<ScopedPackResources> result) {
        if (pack instanceof CompositePackResources composite) {
            CompositePackResourcesAccess access = (CompositePackResourcesAccess) composite;
            add(access.getPrimaryPackResources(), result);
            for (PackResources layer : access.getPackResourcesStack()) add(layer, result);
        } else if (pack instanceof VfsBackedPackResources vfsBacked) {
            result.add(vfsBacked.asScopedPackResources());
        } else if (pack instanceof PathPackResources path) {
            result.add(new ScopedVanillaPathResourcePack(((PathPackResourcesMixin) path).getRoot(), path));
        } else if (pack instanceof FilePackResources file) {
            SharedZipFileAccessAccess access = (SharedZipFileAccessAccess) ((FilePackResourcesMixin) file).getZipFileAccess();
            ZipFile zip = access.invokeGetOrCreateZipFile();
            if (zip != null) result.add(new ScopedVanillaFileResourcePack(zip));
        }
    }
}
