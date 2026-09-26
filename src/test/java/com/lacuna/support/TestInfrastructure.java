package com.lacuna.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

/**
 * PostgreSQL and MinIO in Docker, the same images as compose.yaml, for tests that start the application:
 * {@code @ImportTestcontainers(TestInfrastructure.class)}. The containers are started once and shared by every test
 * class.
 */
public final class TestInfrastructure {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    // MinIO no longer publishes images; Chainguard builds this one from MinIO's sources.
    static final MinIOContainer MINIO = new MinIOContainer(
            DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio"))
            // MinIO moves files within its data folder, which fails across the image's layers.
            .withTmpFs(Map.of("/data", "rw"));

    private TestInfrastructure() {
    }

    @DynamicPropertySource
    static void s3(DynamicPropertyRegistry registry) {
        registry.add("lacuna.storage.s3.endpoint", MINIO::getS3URL);
        registry.add("lacuna.storage.s3.access-key", MINIO::getUserName);
        registry.add("lacuna.storage.s3.secret-key", MINIO::getPassword);
    }
}
