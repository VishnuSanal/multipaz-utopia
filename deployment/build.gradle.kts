// Brewery demo deployment: build and run a standalone Docker image.
// The image bundles the backend's fat (shadow) jar and runs it directly.

val backendShadowJar = ":organizations:brewery:backend:shadowJar"

// Prefer podman (no commercial license) over docker, matching the SDK's convention.
fun containerTool(): String =
    if (file("/usr/bin/podman").exists() ||
        file("/usr/local/bin/podman").exists() ||
        file("/opt/homebrew/bin/podman").exists()) {
        "podman"
    } else {
        "docker"
    }

fun nativePlatform(): String = when (System.getProperty("os.arch")) {
    "aarch64", "arm64" -> "linux/arm64"
    else -> "linux/amd64"
}

tasks.register<Exec>("buildDockerImage") {
    group = "deployment"
    description = "Build the Brewery demo Docker image for the native architecture"
    dependsOn(backendShadowJar)
    workingDir = rootProject.projectDir
    commandLine(
        containerTool(), "build",
        "--platform", nativePlatform(),
        "-f", "deployment/Dockerfile",
        "-t", "multipaz/brewery:latest",
        "."
    )
}

tasks.register<Exec>("runDockerImage") {
    group = "deployment"
    description = "Run the Brewery demo Docker image locally on port 8010"
    commandLine(
        containerTool(), "run", "--rm",
        "-p", "8010:8010",
        "multipaz/brewery:latest"
    )
}
