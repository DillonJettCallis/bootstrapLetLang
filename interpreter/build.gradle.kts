plugins {
  kotlin("multiplatform") version "1.3.61"
}

repositories {
  jcenter()
}

kotlin {
  jvm()

  linuxX64("native") {
    binaries {
      executable()
    }
  }

  sourceSets {
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

    jvm().compilations["main"].defaultSourceSet {
      dependencies {
        implementation(kotlin("stdlib-jdk8"))
      }
    }

    jvm().compilations["test"].defaultSourceSet {
      dependencies {
        implementation(kotlin("test-junit"))
      }
    }

  }
}
