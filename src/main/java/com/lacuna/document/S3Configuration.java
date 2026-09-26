package com.lacuna.document;

import com.lacuna.config.LacunaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
class S3Configuration {

    @Bean
    S3Client s3Client(LacunaProperties properties) {
        var settings = properties.storage().s3();
        var builder = S3Client.builder().region(Region.of(settings.region()));
        if (settings.endpoint() != null) {
            // S3-compatible servers such as MinIO take the bucket in the path, not in the host name.
            builder.endpointOverride(settings.endpoint()).forcePathStyle(true);
        }
        if (StringUtils.hasText(settings.accessKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())));
        }
        return builder.build();
    }
}
