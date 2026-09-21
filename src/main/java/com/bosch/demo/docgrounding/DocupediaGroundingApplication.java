package com.bosch.demo.docgrounding;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DocupediaGroundingApplication {

    private static final String DEFAULT_TRUST_STORE = "certs/proxy-truststore.jks";

    public static void main(String[] args) {
        configureTrustStore();
        SpringApplication.run(DocupediaGroundingApplication.class, args);
    }

    /**
     * Must run before Spring starts: the JVM initialises the default SSLContext on the very
     * first TLS connection, after which javax.net.ssl.* changes are ignored. The truststore
     * contains the CA of the TLS-intercepting corporate proxy (scripts/import-proxy-cert.ps1).
     */
    private static void configureTrustStore() {
        String configured = System.getProperty("javax.net.ssl.trustStore");
        if (configured != null && !configured.isBlank()) {
            return; // explicitly provided via -D, respect it
        }

        String path = System.getenv("TRUST_STORE_PATH");
        if (path == null || path.isBlank()) {
            path = DEFAULT_TRUST_STORE;
        }

        Path trustStore = Path.of(path).toAbsolutePath();
        if (!Files.isRegularFile(trustStore)) {
            System.out.println("[startup] No custom truststore at " + trustStore + " - using JDK defaults");
            return;
        }

        String password = System.getenv("TRUST_STORE_PASSWORD");
        System.setProperty("javax.net.ssl.trustStore", trustStore.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", (password == null || password.isBlank()) ? "changeit" : password);
        System.out.println("[startup] Using custom truststore " + trustStore);
    }
}
