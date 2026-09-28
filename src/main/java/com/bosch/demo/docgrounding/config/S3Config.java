package com.bosch.demo.docgrounding.config;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
public class S3Config {
    @Bean
    public S3Client s3Client(AppProperties properties) {
        AppProperties.S3 s3 = properties.getS3();
        System.out.println("Endpoint = [" + s3.getEndpoint() + "]");

        System.out.println("Region = [" + s3.getRegion() + "]");

        System.out.println("Bucket = [" + s3.getBucket() + "]");

        return S3Client.builder()
                .endpointOverride(URI.create(s3.getEndpoint()))
                .region(Region.of(s3.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3.isPathStyleAccess())
                        .build())
                .build();
    }
}
