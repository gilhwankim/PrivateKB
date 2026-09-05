plugins {
    java
    id("org.springframework.boot") version "4.1.0"
}

group = "io.privatekb"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation(platform("org.springframework.modulith:spring-modulith-bom:2.1.0"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.apache.tika:tika-core:3.3.2")
    implementation("org.apache.tika:tika-parsers-standard-package:3.3.2")

    runtimeOnly("org.postgresql:postgresql")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:4.1.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.test {
    useJUnitPlatform {
        excludeTags("container", "ocr-runtime", "document-runtime")
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs the PostgreSQL/pgvector Testcontainers smoke test."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("container")
    }
    shouldRunAfter(tasks.test)
}

val progressiveIndexingRuntimeTest by tasks.registering(Test::class) {
    description = "동봉 PostgreSQL·pgvector에서 점진 색인 묶음 저장을 검증한다."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("runtime-db")
    }
    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }
}

val ocrRuntimeTest by tasks.registering(Test::class) {
    description = "Runs the opt-in Windows Tesseract OCR runtime smoke test."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("ocr-runtime")
    }
    shouldRunAfter(tasks.test)
}

val documentRuntimeTest by tasks.registering(Test::class) {
    description = "지정한 로컬 문서의 본문을 출력하지 않고 파싱을 검증한다."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/LocalDocumentExtractionRuntimeTest.class")
    environment("PRIVATEKB_TEST_DOCUMENT", providers.environmentVariable("PRIVATEKB_TEST_DOCUMENT").getOrElse(""))
    providers.environmentVariable("PRIVATEKB_TEST_JAVA").orNull?.let { executable = it }
    useJUnitPlatform {
        includeTags("document-runtime")
    }
    outputs.upToDateWhen { false }
}

val packagedRuntimeTest by tasks.registering(Test::class) {
    description = "실제 배포용 Java로 문자 인코딩과 Office 파싱을 검증한다."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/LegacyOfficeCharsetTest.class", "**/TikaDocumentFormatSupportTest.class")
    executable = providers.environmentVariable("PRIVATEKB_TEST_JAVA").getOrElse(
        layout.projectDirectory.file("frontend/src-tauri/runtime/java/bin/java.exe").asFile.absolutePath
    )
    outputs.upToDateWhen { false }
}
