plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

springBoot {
    mainClass.set("ai.rojan.backend.bootstrap.BackendApplicationKt")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":infrastructure"))
    implementation(project(":api"))

    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    // BackendApplication references @EntityScan/@EnableJpaRepositories/@EnableJpaAuditing
    // directly; infrastructure's own data-jpa dependency is `implementation`-scoped
    // (Gradle default encapsulation) so it doesn't flow to this module's compile classpath.
    implementation(libs.spring.boot.starter.data.jpa)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.apache.httpclient5)
    testImplementation(libs.zonky.embedded.database.spring.test)
    // The ZONKY (native, no-Docker) provider implementation — the module
    // above only wires providers into Spring, it doesn't bundle any of them.
    testImplementation(libs.zonky.embedded.postgres)
    // Pinned explicitly (rather than left to the library's own downloader)
    // so it resolves through Gradle's normal, already-proven-reliable Maven
    // Central path. Windows-only for now, matching local dev — revisit if
    // CI ever runs this on Linux.
    testRuntimeOnly(libs.zonky.postgres.binaries.windows.amd64)
    testRuntimeOnly(libs.commons.compress)
    testRuntimeOnly(libs.commons.lang3)
}
