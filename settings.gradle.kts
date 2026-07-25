rootProject.name = "ChunkCarts"

// loader/ and its Stonecutter subprojects are deliberately absent — see SPEC §12.
// The build order is skeleton first, adapters last.
include("core")
include("bukkit")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
