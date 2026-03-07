import org.gradle.api.tasks.testing.Test

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}

val projectPathContainsNonAscii = rootProject.projectDir.absolutePath.any { it.code > 127 }
val testMirrorRoot = File(
    System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir"),
    "rikkahub-test-runtime"
)

subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("file.encoding", "UTF-8")
        jvmArgs("-Dfile.encoding=UTF-8")
        defaultCharacterEncoding = "UTF-8"

        if (projectPathContainsNonAscii) {
            doFirst {
                val mirrorRoot = testMirrorRoot.resolve(
                    "${project.path.removePrefix(":").replace(':', '_')}/$name"
                )
                if (mirrorRoot.exists()) {
                    mirrorRoot.deleteRecursively()
                }
                mirrorRoot.mkdirs()

                val rootPath = rootProject.projectDir.toPath()
                val mirroredFiles = mutableMapOf<String, File>()

                fun mirror(file: File): File {
                    mirroredFiles[file.absolutePath]?.let { return it }
                    if (!file.exists()) {
                        mirroredFiles[file.absolutePath] = file
                        return file
                    }
                    if (!file.toPath().startsWith(rootPath)) {
                        mirroredFiles[file.absolutePath] = file
                        return file
                    }

                    val relativePath = rootPath.relativize(file.toPath()).toString()
                    val target = mirrorRoot.resolve(relativePath)
                    if (file.isDirectory) {
                        copy {
                            from(file)
                            into(target)
                        }
                    } else {
                        target.parentFile.mkdirs()
                        copy {
                            from(file)
                            into(target.parentFile)
                        }
                    }
                    mirroredFiles[file.absolutePath] = target
                    return target
                }

                testClassesDirs = files(testClassesDirs.files.map(::mirror))
                classpath = files(classpath.files.map(::mirror))
                workingDir = mirrorRoot
            }
        }
    }
}
