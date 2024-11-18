import build.wallet.gradle.logic.extensions.targets

plugins {
  id("build.wallet.kmp")
}

kotlin {
  targets(ios = true, jvm = true)

  sourceSets {
    commonMain {
      dependencies {
        api(projects.shared.accountPublic)
        api(projects.shared.authPublic)
        api(projects.shared.bitcoinPublic)
        api(projects.shared.cloudBackupPublic)
        api(projects.shared.f8ePublic)
        api(projects.shared.f8eClientPublic)
        api(projects.shared.featureFlagPublic)
        api(projects.shared.ktorClientPublic)
        api(projects.shared.timePublic)
        implementation(projects.shared.serializationPublic)
      }
    }

    val commonTest by getting {
      dependencies {
        implementation(projects.shared.bitcoinFake)
        implementation(projects.shared.bitkeyPrimitivesFake)
      }
    }
  }
}
