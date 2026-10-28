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
    id("org.owasp.dependencycheck") version "13.0.0"
}

allprojects {
    group = "ai.rojan.backend"
    version = "1.0.0"
}

// ROJAN_BACKEND_SECURITY_AUDIT_v1.md §9 / §11.3 (the proposed-but-not-implemented CI gap): no
// dependency-vulnerability scan exists anywhere in backend-ci.yml. This is deliberately report-only
// for now (failBuildOnCVSS set above the real maximum, so it can never fail a build) - per the audit's
// own instruction to "establish a baseline report before enforcing a blocking threshold." Switching to
// a real gate (a finite failBuildOnCVSS) is its own separate decision, once this baseline has actually
// been reviewed - not performed by this pass. `dependencyCheckAggregate` scans every subproject from
// the root in one pass (the plugin's own recommended entry point for a multi-module Gradle build).
dependencyCheck {
    failBuildOnCVSS = 11.0f
    formats = listOf("HTML", "JSON")
    // No suppression file yet - none has been reviewed/authorized. Per the audit's own requirement
    // ("no blanket suppressions... documented justification, scope, owner, and expiry/review date for
    // every suppression"), a suppressions file is added only once a specific false positive has
    // actually been triaged against this baseline - not pre-emptively.
    nvd {
        // No NVD API key is configured in this environment - the plugin falls back to its own
        // unauthenticated, heavily-rate-limited public feed, which is known to be extremely slow or to
        // time out entirely for a first run. This is reported honestly in the audit, not worked around.
    }
    // KNOWN, UNRESOLVED LOCAL-VERIFICATION LIMITATION (not fixed in this pass - see
    // ROJAN_BACKEND_SECURITY_AUDIT_v1.md §12.4): this environment's outbound requests to
    // https://www.cisa.gov/.../known_exploited_vulnerabilities.json return 403 Forbidden (a known,
    // documented upstream issue - dependency-check/DependencyCheck#5711, #7929). The standard fix is
    // disabling the supplementary CISA KEV analyzer, but this pass could not locate the correct Gradle
    // DSL property to do so within a reasonable number of attempts (2 different AI-summarized doc
    // answers for the property name - `knownExploited.enabled` and `kev.enabled` - both failed to
    // compile against this plugin's real extension; a direct source read of the Gradle extension class
    // found no KEV-related property exposed there at all). Reported honestly as unresolved rather than
    // guessed a 3rd time. NVD remains the primary, unaffected vulnerability source either way.
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom(SpringBootPlugin.BOM_COORDINATES)
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
