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
    // Pass 47 (scanner readiness prep, owner-authorized): mirrors PR #15's own plugin declaration
    // exactly (same version, same artifact) - PR #17's own build.gradle.kts has no trace of this
    // plugin at all (confirmed, Pass 46), so dependencyCheckAggregate does not exist here without
    // this line. NOT pushed to PR #17's branch yet - staged in an isolated local branch
    // (prep/dependency-check-on-pr17) pending owner review of this exact diff.
    id("org.owasp.dependencycheck") version "13.0.0"
}

// Pass 47: OWASP Dependency-Check config, staged for owner review - not yet active on any pushed
// branch. Mirrors PR #15's own config with 2 differences, both flagged for owner decision below:
//
// 1. failBuildOnCVSS stays at 11.0f (above the real CVSS ceiling of 10) - same as PR #15. This is a
//    POLICY decision (report-only vs. a real blocking gate) and is NOT changed here without
//    separate, explicit owner authorization - ROJAN_BACKEND_SECURITY_AUDIT_v1.md §11.3 already
//    frames this as its own future decision, not performed by this pass either.
//
// 2. The CISA KEV analyzer is left at its default (enabled = true), NOT silently disabled, even
//    though a real, verified way to disable it now exists (see the comment on `kev { }` below) -
//    confirmed this pass by decompiling the actual dependency-check-gradle 13.0.0 plugin jar's real
//    extension classes (DependencyCheckExtension -> AnalyzerExtension.kev() -> KEVExtension.
//    setEnabled(Boolean)), not by trusting a search-engine summary (2 earlier, wrong guesses -
//    `knownExploited.enabled` and a top-level `kev.enabled` - are documented in
//    ROJAN_BACKEND_SECURITY_AUDIT_v1.md §12.4). Disabling it is a real, available option, but
//    disabling a security analyzer is a policy choice for the owner to make explicitly, not
//    something this prep silently does to make a future run look cleaner.
dependencyCheck {
    failBuildOnCVSS = 11.0f
    formats = listOf("HTML", "JSON")
    nvd {
        // Reads NVD_API_KEY from a GitHub Actions secret, via the workflow's own `env:` block -
        // never hardcoded here or anywhere else, never printed, never logged, never read outside
        // of this one pass-through at CI time. Whether this secret currently exists on the
        // repository has NOT been confirmed from this diff alone. The exact behavior of this
        // plugin version when the value is absent or empty is similarly not confirmed by this
        // diff - it has not been exercised from this exact configuration. A real scan is not
        // permitted until the secret's presence is confirmed and execution is separately
        // authorized.
        apiKey = System.getenv("NVD_API_KEY")
    }
    // KNOWN, DOCUMENTED LIMITATION (ROJAN_BACKEND_SECURITY_AUDIT_v1.md §12.4): this environment's
    // outbound requests to the CISA KEV feed return 403 Forbidden (a known, filed upstream issue -
    // dependency-check/DependencyCheck#5711, #7929). Left at its default (enabled) deliberately -
    // see the comment above this block. If the owner authorizes disabling it, the real, verified
    // DSL (confirmed against the plugin's actual compiled extension classes this pass, not a
    // doc/search summary) is:
    //     analyzers {
    //         kev {
    //             enabled = false
    //         }
    //     }
    // (KEVExtension also exposes a `url` property - pointing it at a private mirror of the CISA
    // feed is a second, untested-in-this-environment alternative to disabling it outright.)
    //
    // NVD data-feed caching (investigated per this pass's explicit request, not applied without a
    // matching workflow change - see the prepared backend-ci.yml diff): the plugin's real
    // `data { directory = "..." }` property (confirmed via DataExtension's compiled class) is the
    // path a GitHub Actions `actions/cache` step should target to persist the downloaded NVD feed
    // across runs. `cache { }` (CacheExtension) only toggles caching of the OSS Index/Central/
    // NodeAudit analyzer results, NOT the NVD feed itself - a materially different thing than what
    // its name might suggest; not used for this purpose.
    data {
        directory = "${rootProject.projectDir}/.dependency-check-data"
    }
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
        // @field:Size(max = 128) permits passwords past that boundary).
        //
        // F4 (this remediation): GHSA-mf92-479x-3373 ("HTTP Headers Are not Written
        // Under Some Conditions") has NO fix anywhere in the 6.3.x line (first_patched_
        // version: null for <=6.3.10, confirmed via the real GitHub Advisory API) -
        // fixed only at 6.5.9+. Bumped to 6.5.11 (latest 6.5.x release), together with
        // the F1 Framework bump below, since spring-security-core:6.5.11's own POM
        // declares spring-core:6.2.19 as its compile dependency - these two are an
        // officially-paired, same-major-line (6.x) version combination, not a Boot 4.x/
        // Spring 7 migration.
        dependencies {
            // F1 CORRECTION (re-verified directly against the real GitHub Advisory API,
            // not restated from memory): GHSA-pc63-qcmh-9cmg's vulnerable range extends
            // through 6.2.19 too (">=6.2.0, <=6.2.19", first_patched_version: null) - the
            // real fix floor is 7.0.9+ only. Bumping to 6.2.19 does NOT close F1; it
            // remains UPSTREAM_FIX_UNAVAILABLE (XsltView itself confirmed unused anywhere
            // in this codebase - low reachability, same as before). 6.2.19 is applied
            // here only because spring-security-core:6.5.11 (F4's real fix, below)
            // declares it as its own required compile dependency, and because it is a
            // real prerequisite for testing whether springdoc can now load (F12, tested
            // separately, not assumed).
            dependency("org.springframework:spring-core:6.2.19")
            dependency("org.springframework:spring-web:6.2.19")
            dependency("org.springframework:spring-webmvc:6.2.19")
            dependency("org.springframework:spring-context:6.2.19")
            dependency("org.springframework:spring-beans:6.2.19")
            dependency("org.springframework:spring-aop:6.2.19")
            dependency("org.springframework:spring-expression:6.2.19")
            dependency("org.springframework:spring-tx:6.2.19")
            dependency("org.springframework:spring-orm:6.2.19")
            dependency("org.springframework:spring-jdbc:6.2.19")
            dependency("org.springframework:spring-jcl:6.2.19")
            dependency("org.springframework.security:spring-security-core:6.5.11")
            dependency("org.springframework.security:spring-security-config:6.5.11")
            dependency("org.springframework.security:spring-security-web:6.5.11")
            dependency("org.springframework.security:spring-security-crypto:6.5.11")
            // F8-F11: tomcat-embed-core 10.1.31 (transitive) matches the vulnerable
            // range of 9 distinct GHSA IDs (GHSA-r29c-68gh-xp6x, GHSA-563x-q5rq-57qp,
            // GHSA-wmwf-9ccg-fff5, and 6 more under F11), each fixed somewhere in the
            // 10.1.45-10.1.58 range. 10.1.61 is the latest published 10.1.x release
            // (confirmed via Maven Central), covering every one of them - a same-
            // major-line patch bump, independent of the Framework/Security pair above.
            dependency("org.apache.tomcat.embed:tomcat-embed-core:10.1.61")
            dependency("org.apache.tomcat.embed:tomcat-embed-el:10.1.61")
            dependency("org.apache.tomcat.embed:tomcat-embed-websocket:10.1.61")
            // F16: kafka-clients 3.7.1 is vulnerable to GHSA-5qcv-4rpc-jp93 (producer
            // message corruption/misrouting via a buffer-pool race), fixed 3.9.2.
            dependency("org.apache.kafka:kafka-clients:3.9.2")
            // F17: postgresql JDBC 42.7.4 is in GHSA-j92g-9f8w-j867's vulnerable range
            // (42.7.4-42.7.11, fixed 42.7.12) - confirmed not exploitable here today
            // (no channelBinding=require anywhere in this repo's JDBC config), bumped
            // anyway as free hygiene with zero behavior change.
            dependency("org.postgresql:postgresql:42.7.14")
            // F12 remediation (this pass): both real GHSA ranges for jackson-databind
            // (GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54) are fixed exactly at 2.18.11
            // (confirmed via the real GitHub Advisory API). DEMONSTRATED (not assumed):
            // importing jackson-bom:2.18.11 via `imports{}` did NOT work - the real
            // resolved graph still showed jackson-bom:2.17.3 winning (confirmed via
            // `./gradlew :bootstrap:dependencies`), because io.spring.dependency-
            // management's BOM-import precedence does not let a later `imports{}` entry
            // override Boot's own already-declared jackson-bom management. Explicit
            // `dependency()` overrides (this engagement's already-proven mechanism for
            // every other finding in this file) DO take precedence over BOM management,
            // so every Jackson-family artifact actually present in the resolved graph is
            // listed individually below, not just jackson-databind - avoiding the exact
            // mismatch (jackson-core/module-kotlin silently staying at 2.17.3) that broke
            // the first 2 real test runs of this remediation.
            dependency("com.fasterxml.jackson.core:jackson-databind:2.18.11")
            dependency("com.fasterxml.jackson.core:jackson-core:2.18.11")
            dependency("com.fasterxml.jackson.core:jackson-annotations:2.18.11")
            dependency("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.11")
            dependency("com.fasterxml.jackson.module:jackson-module-parameter-names:2.18.11")
            dependency("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.18.11")
            dependency("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.11")
            dependency("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.11")
            dependency("com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.18.11")
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
