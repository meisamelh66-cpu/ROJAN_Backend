plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))

    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)

    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.kafka)
    compileOnly(libs.jakarta.servlet.api)

    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)

    implementation(libs.aws.s3)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)

    // App Release Update-Check Fix (V52): a standalone, no-Spring-context test drives Flyway's own
    // Java API directly against a disposable embedded Postgres, to prove the migration's own
    // backfill - not possible through a @SpringBootTest, which always runs every migration (V52
    // included) before the context (and the test) ever starts. Same ZONKY (native, no-Docker)
    // provider bootstrap's own integration tests already use.
    testImplementation(libs.zonky.embedded.postgres)
    testRuntimeOnly(libs.zonky.postgres.binaries.windows.amd64)
    testRuntimeOnly(libs.commons.compress)
    testRuntimeOnly(libs.commons.lang3)
}
