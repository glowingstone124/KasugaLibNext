package lib.kasuga.core.resource.pack;

import lib.kasuga.core.resource.mixin.PackJarAccess;
import lib.kasuga.core.resource.mixin.PackOverlayAccess;
import lib.kasuga.core.resource.mixin.PackPathAccess;
import lib.kasuga.core.resource.mixin.PackZipAccess;
import lib.kasuga.core.resource.mixin.SharedPackZipAccess;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.OverlayedPackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.neoforge.resource.JarContentsPackResources;

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
        if (pack instanceof OverlayedPackResources overlay) {
            for (PackResources layer : ((PackOverlayAccess) overlay).kasuga$layers()) add(layer, result);
        } else if (pack instanceof VfsBackedPackResources vfs) {
            result.add(vfs.asScopedPackResources());
        } else if (pack instanceof MappedPackResources mapped) {
            result.add(mapped.asScopedPackResources());
        } else if (pack instanceof PathPackResources path) {
            result.add(new ScopedVanillaPathResourcePack(((PackPathAccess) path).kasuga$root(), path));
        } else if (pack instanceof FilePackResources file) {
            PackZipAccess access = (PackZipAccess) file;
            ZipFile zip = ((SharedPackZipAccess) access.kasuga$archive()).kasuga$openArchive();
            if (zip != null) result.add(new ScopedVanillaFileResourcePack(zip, access.kasuga$prefix()));
        } else if (pack instanceof JarContentsPackResources jar) {
            PackJarAccess access = (PackJarAccess) jar;
            result.add(new ScopedJarResourcePack(jar, access.kasuga$contents(), access.kasuga$prefix()));
        }
    }
}
