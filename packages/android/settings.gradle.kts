pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()

        /*
         * sync-kit-android, which is what the sharing half of Keyweb is built
         * on. It is published to GitHub Packages rather than Maven Central,
         * and GitHub Packages requires authentication even for public
         * artifacts — so a token is needed to *build*, not merely to publish.
         *
         * CI sets GITHUB_ACTOR and GITHUB_TOKEN automatically. A developer
         * machine needs gpr.user and gpr.key in ~/.gradle/gradle.properties.
         * Failing loudly here beats a resolution error twenty lines deep that
         * reads as if the dependency does not exist.
         */
        maven {
            url = uri("https://maven.pkg.github.com/keyneom/sync-kit")
            credentials {
                username = githubPackagesUsername()
                password = githubPackagesToken()
            }
        }
        // So a release candidate can be built against before it is published.
        mavenLocal()
    }
}

private fun githubPackagesUsername(): String =
    providers.gradleProperty("gpr.user").orNull
        ?: System.getenv("GITHUB_ACTOR")?.takeIf { it.isNotBlank() }
        ?: error(
            "sync-kit-android comes from GitHub Packages, which needs a username: " +
                "set gpr.user in ~/.gradle/gradle.properties, or export GITHUB_ACTOR " +
                "(GitHub Actions sets it for you).",
        )

private fun githubPackagesToken(): String =
    providers.gradleProperty("gpr.key").orNull
        ?: System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
        ?: error(
            "sync-kit-android comes from GitHub Packages, which needs a token with " +
                "read:packages: set gpr.key in ~/.gradle/gradle.properties, or export " +
                "GITHUB_TOKEN (GitHub Actions sets it for you).",
        )
rootProject.name = "keyweb"
include(":vault")
include(":app")
