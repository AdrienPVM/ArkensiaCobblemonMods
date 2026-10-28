plugins {
    java
}

group = "com.sebastian"
version = "1.0.5"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(files("libs/velocity-proxy.jar"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile> {
    options.release.set(17)
}
