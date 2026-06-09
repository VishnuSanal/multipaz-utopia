rootProject.name = "MultipazUtopiaUniverse"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        mavenLocal()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
	mavenLocal()
        mavenCentral()
        // Multipaz SDK is consumed as published artifacts. Released versions come from Maven
        // Central; -SNAPSHOT versions (the default in gradle/libs.versions.toml) come from the
        // Central snapshots repository, published by the SDK's "Publish to Maven Central" action.
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent {
                includeGroup("org.multipaz")
                snapshotsOnly()
            }
        }
    }
}

include(":organizations:brewery:backend")
include(":organizations:brewery:frontend")
include(":deployment")
