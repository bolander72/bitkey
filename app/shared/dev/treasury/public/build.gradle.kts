import build.wallet.gradle.logic.extensions.targets

plugins {
  id("build.wallet.kmp")
}

kotlin {
  targets(android = true, jvm = true)

  sourceSets {
    commonMain {
      dependencies {
        api(projects.shared.bitcoinPublic)
        api(projects.shared.moneyPublic)
        api(projects.shared.testingPublic)
        implementation(libs.kmp.test.kotest.assertions)
        implementation(projects.shared.bdkBindingsPublic)
        implementation(projects.shared.coroutinesTesting)
        implementation(projects.shared.moneyTesting)
        implementation(projects.shared.resultPublic)
        implementation(projects.shared.stdlibPublic)
      }
    }

    val commonJvmMain by getting {
      dependencies {
        implementation(libs.kmp.aws.secretsmanager)
      }
    }

    val jvmMain by getting {
      dependencies {
        implementation(libs.jvm.bdk)
        implementation(libs.jvm.bitcoin.rpc.client)
      }
    }
  }
}
