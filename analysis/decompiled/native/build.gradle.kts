plugins {
    kotlin("jvm")
    id("jps-compatible")
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

projectTest {
    workingDir = rootDir
}

dependencies {
    implementation(project(":core:deserialization"))
    implementation(project(":compiler:psi"))
    implementation(project(":compiler:frontend.java"))
    implementation(project(":analysis:decompiled:decompiler-to-file-stubs"))
    implementation(project(":analysis:decompiled:decompiler-to-psi"))
    implementation(project(":analysis:decompiled:decompiler-to-stubs"))
    implementation(project(":js:js.serializer"))
    implementation(project(":kotlin-util-klib-metadata"))
    implementation(intellijCore())
}

testsJar()
