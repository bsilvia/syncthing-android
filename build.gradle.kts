// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    extra.apply {
        // Cannot be called "ndkVersion" as that leads to naming collision
        // Changes to this value must be reflected in `./docker/Dockerfile`
        set("ndkVersionShared", "29.0.14206865")

    }
    val kotlinVersion by extra("2.2.0")

    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("com.github.ben-manes:gradle-versions-plugin:0.53.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files
    }
}

tasks.register<Delete>("clean") {
    delete(getLayout().buildDirectory)
}
