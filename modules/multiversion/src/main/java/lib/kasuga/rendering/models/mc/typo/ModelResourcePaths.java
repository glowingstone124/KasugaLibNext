package lib.kasuga.rendering.models.mc.typo;

import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.net.URI;

/** Resolves references contained in model files against the owning resource pack. */
public final class ModelResourcePaths {
    private ModelResourcePaths() {}

    public static @Nullable Identifier relativeTo(Identifier owner, String reference) {
        try {
            URI uri = URI.create(reference);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPath().isBlank()) return null;
            String path = URI.create("/" + owner.getPath()).resolve(uri).normalize().getPath();
            if (path.equals("/") || path.contains("/../")) return null;
            return Identifier.tryBuild(owner.getNamespace(), path.substring(1));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
