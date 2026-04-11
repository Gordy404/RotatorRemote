plugins {
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

tasks.register("incrementBuildNumber") {
    doLast {
        val propsFile = file("gradle.properties")
        if (!propsFile.exists()) return@doLast
        
        val props = java.util.Properties()
        propsFile.inputStream().use { props.load(it) }
        
        val current = props.getProperty("test_build_number")?.toIntOrNull() ?: 0
        val next = (current + 1).toString().padStart(3, '0')
        
        // Only update if it changed
        props.setProperty("test_build_number", next)
        propsFile.outputStream().use { props.store(it, null) }
        println("Build number incremented to $next")
    }
}

// Only hook into the main app's assemble tasks to avoid multiple increments
subprojects {
    if (name == "app" || path == ":RotatorServer:app") {
        tasks.matching { it.name.startsWith("assemble") }.configureEach {
            this.finalizedBy(rootProject.tasks.named("incrementBuildNumber"))
        }
    }
}
