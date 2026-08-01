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
}
