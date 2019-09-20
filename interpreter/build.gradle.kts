plugins {
  kotlin("multiplatform") version "1.3.50"
}

repositories {
  jcenter()
}

kotlin {
  linuxX64("native") {
    binaries {
      executable()
    }
  }
}

dependencies {

}
