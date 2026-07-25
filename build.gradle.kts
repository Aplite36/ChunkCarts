// Root project holds no source. Per-module configuration lives in core/ and bukkit/.
// group and version are set for every project from gradle.properties.

tasks.wrapper {
    gradleVersion = "9.6.1"
    distributionType = Wrapper.DistributionType.BIN
}
