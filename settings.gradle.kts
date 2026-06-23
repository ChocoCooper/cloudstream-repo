rootProject.name = "ChocoCooper"

// This file sets what projects are included. All new projects should get automatically included unless specified in "disabled" variable.

val disabled = listOf<String>()

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}

// Explicitly forcing Gradle to build the Youtube folder
include(":Youtube")
