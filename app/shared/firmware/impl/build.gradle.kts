import build.wallet.gradle.logic.extensions.allTargets

plugins {
  id("build.wallet.kmp")
}

kotlin {
  allTargets()

  sourceSets {
    commonMain {
      dependencies {
        api(projects.shared.firmwarePublic)
        api(projects.shared.loggingPublic)
        api(projects.shared.databasePublic)
        api(projects.shared.queueProcessorPublic)
        implementation(projects.shared.stdlibPublic)
        // TODO: remove dependency on :impl
        implementation(projects.shared.queueProcessorImpl) {
          because("Depends on PeriodicProcessorImpl")
        }
      }
    }

    commonTest {
      dependencies {
        implementation(projects.shared.sqldelightTesting)
        api(projects.shared.memfaultFake)
        api(projects.shared.coroutinesTesting)
      }
    }

    val androidMain by getting {
      dependencies {
        implementation(projects.rust.firmwareFfi)
      }
    }
  }
}
