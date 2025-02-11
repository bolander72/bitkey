plugins {
  kotlin("jvm")
  id("com.google.devtools.ksp")
}

dependencies {
  implementation(libs.jvm.ksp)
  implementation(libs.jvm.kotlinpoet)
  implementation(libs.jvm.kotlinpoet.ksp)
  testImplementation(libs.jvm.compileTesting)
  testImplementation(libs.jvm.compileTesting.ksp)
  testImplementation(kotlin("test"))
  testImplementation(kotlin("test-junit"))
}

layout.buildDirectory = File("_build")
