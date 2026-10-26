/*
 * Wire protocol between the host side ('hot-reload-android-host') and the device runtime
 * ('hot-reload-android-runtime'). Plain Java so that it runs on both the host JVM and ART.
 */
plugins {
    `java-library`
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
}

dependencies {
    testImplementation(libs.junit)
}
