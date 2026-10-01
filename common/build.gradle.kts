plugins {
    `java-library`
}

dependencies {
    val springBootVersion = "3.5.4"

    // api: services touch Lettuce connection types directly through HoldManager
    api(platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
    api("io.lettuce:lettuce-core")

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
