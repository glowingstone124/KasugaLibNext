package builder.kasuga

import org.gradle.api.Plugin
import org.gradle.api.Project

class KasugaGeneratorPlugin implements Plugin<Project> {
    @Override
    void apply(Project target) {
        target.ext.useGenerator = { Map options = [:] ->
            KasugaGenerator.useGenerator(target, options)
        }
    }
}
