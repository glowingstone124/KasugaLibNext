package builder.kasuga

import org.gradle.api.Project
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity

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
        var runGenerator = project.tasks.register('runGenerator', JavaExec) {
            inputs.files(project.sourceSets.codeTemplate.allSource)
                    .withPropertyName('sources').withPathSensitivity(PathSensitivity.RELATIVE)
            dependsOn(preparationTasks)
            // ModDev registers this task when the platform version is configured, which may
            // happen after useGenerator(). Keep the dependency lazy for a clean parallel build.
            dependsOn(project.tasks.matching { it.name == 'createMinecraftArtifacts' })

            outputs.dir("$project.buildDir/generated/sources/codegen/java/main")

            classpath = project.configurations.codeGenDependency
            mainClass = 'lib.kasuga.internal.generator.KasugaCodeGen'
            def symbols = project.sourceSets.main.compileClasspath
            inputs.files(symbols).withPropertyName('symbolClasspath').withNormalizer(ClasspathNormalizer)
            Provider<Set<FileSystemLocation>> classpathEntries = symbols.elements
            def codeTemplateSrcDir = project.sourceSets.codeTemplate.java.srcDirs.first().absolutePath
            def generatedDir = "$project.buildDir/generated/sources/codegen/java/main"
            def buildDirPath = project.buildDir.absolutePath
            def mainSrcDirs = project.sourceSets.main.java.srcDirs
                    .findAll { !it.absolutePath.startsWith(buildDirPath) }
            List<File> sourceRoots = mainSrcDirs.toList() + variantSources
            inputs.files(sourceRoots).withPropertyName('symbolSources').withPathSensitivity(PathSensitivity.RELATIVE)
            args(codeTemplateSrcDir)
            args(generatedDir)
            doFirst {
                def allClasspath = sourceRoots*.absolutePath + classpathEntries.get().asFile*.absolutePath
                args(allClasspath.join(File.pathSeparator))
            }

            javaLauncher.set(project.javaToolchains.launcherFor {
                languageVersion = project.java.toolchain.languageVersion
            })

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
