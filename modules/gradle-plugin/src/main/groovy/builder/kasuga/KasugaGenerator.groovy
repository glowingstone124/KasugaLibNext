package builder.kasuga

import org.gradle.api.Project
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.tasks.JavaExec

import org.gradle.api.provider.Provider

public class KasugaGenerator {
    public static void useGenerator(Project project, Map options = [:]) {
        Object templateDirectory = options.getOrDefault('templates', 'src/codeTemplate/java')
        List<?> preparationTasks = options.getOrDefault('dependsOn', [])
        List<File> variantSources = options.getOrDefault('sources', []).collect { project.file(it) }
        project.configurations {
            codeGenDependency
        }
        project.sourceSets {
            codeTemplate {
                java {
                    setSrcDirs([templateDirectory])
                }
                resources {
                    srcDir 'src/codeTemplate/resources'
                }

                compileClasspath += project.sourceSets.main.output
                runtimeClasspath += project.sourceSets.main.output

                compileClasspath += project.sourceSets.main.compileClasspath
                runtimeClasspath += project.sourceSets.main.runtimeClasspath


                compileClasspath += project.configurations.codeGenDependency
            }
        }
        var minecraftArtifacts = project.tasks.findByName("createMinecraftArtifacts");

        String utilsMinecraftArtifactPath = null
        File utilsArtifactsDir = null
        def utilsProject = project.findProject(':modules:utils')
        if (utilsProject != null) {
            def utilsTask = utilsProject.tasks.findByName("createMinecraftArtifacts")
            if (utilsTask != null) {
                utilsMinecraftArtifactPath = utilsTask.path
            }
            utilsArtifactsDir = new File(utilsProject.buildDir, "moddev/artifacts")
        }

        var runGenerator = project.tasks.register('runGenerator', JavaExec) {

            inputs.files(project.sourceSets.codeTemplate.allSource)
                    .withPropertyName("sources");
            inputs.files(variantSources).withPropertyName('variantSources')
            dependsOn(preparationTasks)

            outputs.dir("$project.buildDir/generated/sources/codegen/java/main")

            classpath = project.configurations.codeGenDependency
            mainClass = 'lib.kasuga.internal.generator.KasugaCodeGen'
            Provider<Set<FileSystemLocation>> provider = project.sourceSets.main.runtimeClasspath.getElements()
            def codeTemplateSrcDir = project.sourceSets.codeTemplate.java.srcDirs.first().absolutePath
            def generatedDir = "$project.buildDir/generated/sources/codegen/java/main"
            def buildDirPath = project.buildDir.absolutePath
            def mainSrcDirs = project.sourceSets.main.java.srcDirs
                    .findAll { !it.absolutePath.startsWith(buildDirPath) }
            args(codeTemplateSrcDir)
            args(generatedDir)
            doFirst{
                def allClasspath = (mainSrcDirs + variantSources)*.absolutePath + provider.get().asFile*.absolutePath
                if (utilsArtifactsDir != null && utilsArtifactsDir.exists()) {
                    utilsArtifactsDir.eachFile { f ->
                        if (f.name.endsWith('.jar') && !f.name.endsWith('-sources.jar')) {
                            allClasspath.add(f.absolutePath)
                        }
                    }
                }
                args(allClasspath.join(File.pathSeparator))
            }

            javaLauncher.set(project.javaToolchains.launcherFor {
                languageVersion = project.java.toolchain.languageVersion
            })

            if(minecraftArtifacts != null)
                dependsOn(minecraftArtifacts)
            if(utilsMinecraftArtifactPath != null)
                dependsOn(utilsMinecraftArtifactPath)
        }
        project.compileJava.dependsOn(runGenerator)

        project.dependencies {
            if (project.findProject(":modules:generator")) {
                codeGenDependency project.findProject(':modules:generator');
            } else if(project.findProject(":KasugaLibNext:modules:generator")) {
                codeGenDependency project.findProject(':KasugaLibNext:modules:generator');
            } else {
                //@TODO:FIXME{
                codeGenDependency 'lib.kasuga:generator:1.0.0';
            }
        }
    }
}
