pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven {
                    name = "XiaoyiHuiJi"
                    url = uri(
                        "https://raw.githubusercontent.com/Stefan99sun/xiaoyi-huiji-sdk/" +
                            "a6e0cbc9fc1c775de118a2c2a8a015fd12defa11/maven",
                    )
                }
            }
            filter { includeModule("io.github.stefan99sun", "xiaoyi-huiji") }
        }
    }
}

rootProject.name = "MuseVisionV2"
include(":app")
