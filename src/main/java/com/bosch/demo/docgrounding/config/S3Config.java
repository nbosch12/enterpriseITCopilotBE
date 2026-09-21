package com.bosch.demo.docgrounding.config;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Slf4j
@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(AppProperties properties) {
        AppProperties.S3 s3 = properties.getS3();
        applyProxySystemProperties(s3.getProxy());

        log.info("Configuring S3 client endpoint={} region={} bucket={}", s3.getEndpoint(), s3.getRegion(), s3.getBucket());

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

    /**
     * Behind a corporate proxy S3 calls fail with HTTP 407. The AWS SDK default HTTP client
     * reads the standard JVM proxy system properties, so we set them from our configuration.
     */
    private void applyProxySystemProperties(AppProperties.Proxy proxy) {
        if (proxy == null || !proxy.isEnabled() || proxy.getHost() == null || proxy.getHost().isBlank()) {
            log.info("S3 client configured without an explicit proxy");
            return;
        }

        String port = String.valueOf(proxy.getPort());
        for (String scheme : new String[] { "http", "https" }) {
            System.setProperty(scheme + ".proxyHost", proxy.getHost());
            System.setProperty(scheme + ".proxyPort", port);
            if (proxy.getUsername() != null && !proxy.getUsername().isBlank()) {
                System.setProperty(scheme + ".proxyUser", proxy.getUsername());
                System.setProperty(scheme + ".proxyPassword", proxy.getPassword() == null ? "" : proxy.getPassword());
            }
        }
        if (proxy.getNonProxyHosts() != null && !proxy.getNonProxyHosts().isBlank()) {
            System.setProperty("http.nonProxyHosts", proxy.getNonProxyHosts().replace(',', '|'));
        }

        log.info("S3 client using proxy {}:{}", proxy.getHost(), proxy.getPort());
    }
}
