plugins {
    java
    id("io.gatling.gradle") version "3.16.0"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
