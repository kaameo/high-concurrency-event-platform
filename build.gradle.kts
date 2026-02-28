plugins {
	java
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.kaameo"
version = "0.0.1-SNAPSHOT"
description = "High-concurrency first-come-first-served event platform"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-batch")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")

	// QueryDSL
	implementation("com.querydsl:querydsl-jpa:5.1.0:jakarta")
	annotationProcessor("com.querydsl:querydsl-apt:5.1.0:jakarta")
	annotationProcessor("jakarta.persistence:jakarta.persistence-api")
	annotationProcessor("jakarta.annotation:jakarta.annotation-api")

	// Redisson (Spring Boot 4 호환)
	implementation("org.redisson:redisson-spring-boot-starter:4.0.0")

	// UUID v7
	implementation("com.github.f4b6a3:uuid-creator:6.0.0")

	// Springdoc OpenAPI
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.0")

	// Flyway (Spring Boot 4는 starter 필수)
	implementation("org.springframework.boot:spring-boot-flyway")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")

	compileOnly("org.projectlombok:lombok")
	runtimeOnly("org.postgresql:postgresql")
	annotationProcessor("org.projectlombok:lombok")

	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-redis-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.testcontainers:postgresql:1.19.7")
	testImplementation("org.testcontainers:kafka:1.19.7")
	testImplementation("org.testcontainers:junit-jupiter:1.19.7")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
	mainClass.set("com.kaameo.event_platform.EventPlatformApplication")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
