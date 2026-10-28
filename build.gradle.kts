import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "ai.rojan.backend"
    version = "1.0.0"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom(SpringBootPlugin.BOM_COORDINATES)
        }
        // Security findings F3/F5 (ROJAN_BACKEND_SECURITY_AUDIT_v1.md): Spring Boot
        // 3.3.5's own BOM pins spring-security-* to 6.3.4, which is vulnerable to
        // GHSA-q3v6-hm2v-pw99 (authorization bypass for case-sensitive comparisons,
        // fixed 6.3.5) and GHSA-mg83-c7gq-rv5c (BCryptPasswordEncoder.matches()
        // incorrectly returns true for passwords >72 chars when the first 72 match,
        // fixed 6.3.8 - confirmed exploitable here since RegisterRequest's own
        // @field:Size(max = 128) permits passwords past that boundary). 6.3.10 is
        // the latest release in the same 6.3.x line Spring Boot 3.3.5 already
        // targets - a same-minor-line patch override, not a framework migration.
        dependencies {
            dependency("org.springframework.security:spring-security-core:6.3.10")
            dependency("org.springframework.security:spring-security-config:6.3.10")
            dependency("org.springframework.security:spring-security-web:6.3.10")
            dependency("org.springframework.security:spring-security-crypto:6.3.10")
            // F16: kafka-clients 3.7.1 is vulnerable to GHSA-5qcv-4rpc-jp93 (producer
            // message corruption/misrouting via a buffer-pool race), fixed 3.9.2.
            dependency("org.apache.kafka:kafka-clients:3.9.2")
            // F17: postgresql JDBC 42.7.4 is in GHSA-j92g-9f8w-j867's vulnerable range
            // (42.7.4-42.7.11, fixed 42.7.12) - confirmed not exploitable here today
            // (no channelBinding=require anywhere in this repo's JDBC config), bumped
            // anyway as free hygiene with zero behavior change.
            dependency("org.postgresql:postgresql:42.7.14")
            // F12 ATTEMPTED, REVERTED: jackson-databind 2.18.12 broke a real endpoint
            // (GET /v3/api-docs -> 500, "OpenAPI docs describe the auth and dashboard
            // endpoints" test) - springdoc-openapi-starter-webmvc-ui 2.6.0 (gradle/
            // libs.versions.toml) predates Jackson 2.18.x and is not compatible with it.
            // Isolating this bump (Netty kept, tested separately) rather than reverting
            // both findings' fixes at once. F12 itself remains open, `UPSTREAM_FIX_
            // UNAVAILABLE_WITHOUT_FURTHER_WORK` - a real fix needs either a springdoc
            // version bump (itself needing its own compatibility check, not attempted
            // this pass) or confirming this specific failure mode is otherwise safe to
            // accept, neither decided here.
            // dependency("com.fasterxml.jackson.core:jackson-databind:2.18.12")
            // F15: netty-* 4.1.114.Final (pulled in transitively via lettuce-core's
            // Redis client and the AWS SDK's netty-nio-client) carries several real
            // TLS/SNI-handling advisories (GHSA-c653-97m9-rcg9, GHSA-x4gw-5cx5-pgmh,
            // GHSA-3qp7-7mw8-wx86, GHSA-4g8c-wm8x-jfhw), fixed at 4.1.135-4.1.137.Final.
            // 4.1.139.Final is the latest 4.1.x release (same major line).
            dependency("io.netty:netty-common:4.1.139.Final")
            dependency("io.netty:netty-handler:4.1.139.Final")
            dependency("io.netty:netty-buffer:4.1.139.Final")
            dependency("io.netty:netty-transport:4.1.139.Final")
            dependency("io.netty:netty-codec:4.1.139.Final")
            dependency("io.netty:netty-codec-http:4.1.139.Final")
            dependency("io.netty:netty-codec-http2:4.1.139.Final")
            dependency("io.netty:netty-resolver:4.1.139.Final")
            dependency("io.netty:netty-transport-native-unix-common:4.1.139.Final")
            dependency("io.netty:netty-transport-classes-epoll:4.1.139.Final")
        }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    dependencies {
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }
}
