plugins {
  kotlin("multiplatform") version "1.9.21"
}

repositories {
  mavenCentral()
}

kotlin {
  targets.all {
    compilations.all {
      compilerOptions.configure {
        freeCompilerArgs.add("-Xexpect-actual-classes")
      }
    }
  }

  jvm()

  linuxX64("native") {
    binaries {
      executable()
    }
  }

  sourceSets {
    all {
      languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
    }

    named("commonMain") {
      dependencies {
        implementation(kotlin("stdlib-common"))
      }
    }

    named("commonTest") {
      dependencies {
        implementation(kotlin("test-common"))
        implementation(kotlin("test-annotations-common"))
      }
    }

    jvm().compilations["test"].defaultSourceSet {
      dependencies {
        implementation(kotlin("test-junit"))
      }
    }

  }
}
