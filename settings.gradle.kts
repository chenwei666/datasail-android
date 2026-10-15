pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "DataSail"
include(":app", ":core", ":sqlserver-android", ":sasl-android")

include(":postgresql-android")

include(":mariadb-android")

include(":mongodb-core-android")

include(":mongodb-sync-android")
