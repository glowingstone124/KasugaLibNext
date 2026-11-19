package lib.kasuga.core.resource.pack;

import java.io.IOException;
import java.util.List;

public interface HierarchicalScopedPackResources extends ScopedPackResources {
    public List<String> list(String prefix, String path) throws IOException;
}
