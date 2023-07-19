plugins {
    kotlin("jvm")
    id("jps-compatible")
}

project.updateJvmTarget("1.8")

dependencies {
    implementation(kotlinStdlib())
    api(project(":kotlin-scripting-dependencies"))

    implementation("org.apache.maven:maven-core:3.9.3")
    implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.13")
    implementation("org.apache.maven.resolver:maven-resolver-transport-file:1.9.13")
    implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.13")
    implementation("org.apache.maven.resolver:maven-resolver-impl:1.9.13")
    implementation("commons-io:commons-io:2.11.0")
    implementation("com.google.inject:guice:5.1.0")

    testImplementation(projectTests(":kotlin-scripting-dependencies"))
    testImplementation(commonDependency("junit"))
    testRuntimeOnly("org.slf4j:slf4j-nop:1.7.36")
    testImplementation(commonDependency("org.jetbrains.kotlin:kotlin-reflect")) { isTransitive = false }
    testImplementation(commonDependency("org.jetbrains.kotlinx", "kotlinx-coroutines-core"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>> {
    kotlinOptions.freeCompilerArgs += listOf(
        "-Xallow-kotlin-package"
    )
}

publish()

runtimeJar()
sourcesJar()
javadocJar()
