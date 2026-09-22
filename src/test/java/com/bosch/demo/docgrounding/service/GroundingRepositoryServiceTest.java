package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroundingRepositoryServiceTest {

    private static final String SINGLE = "f0bf939b-59ef-4211-b8f0-db053c46cbad";

    private GroundingRepositoryService service(String repositoryId, String rootPrefix, boolean enforce) {
        AppProperties properties = new AppProperties();
        properties.getGrounding().setRepositoryId(repositoryId);
        properties.getGrounding().setRootPrefix(rootPrefix);
        properties.getGrounding().setEnforceSingleRepository(enforce);
        return new GroundingRepositoryService(properties);
    }

    @Test
    void configuredRepositoryAlwaysWinsWhenEnforced() {
        GroundingRepositoryService service = service(SINGLE, "", true);
        assertEquals(SINGLE, service.resolveRepositoryId(null));
        assertEquals(SINGLE, service.resolveRepositoryId("some-older-repository"));
    }

    @Test
    void requestedRepositoryUsedOnlyWhenNotEnforced() {
        assertEquals("other", service(SINGLE, "", false).resolveRepositoryId("other"));
        assertEquals(SINGLE, service(SINGLE, "", false).resolveRepositoryId(" "));
    }

    @Test
    void failsClearlyWhenNothingConfiguredOrRequested() {
        assertThrows(IllegalStateException.class, () -> service("", "", true).resolveRepositoryId(null));
    }

    @Test
    void emptyRootKeepsExistingKeysAndCoversWholeBucket() {
        GroundingRepositoryService service = service(SINGLE, "", true);
        assertEquals("docupedia/QR_CODE/1/chunk-0.txt", service.key("docupedia/QR_CODE/1/chunk-0.txt"));
        assertEquals("/", service.includePath());
    }

    @Test
    void rootPrefixIsNormalised() {
        GroundingRepositoryService service = service(SINGLE, "/knowledge/", true);
        assertEquals("knowledge/", service.rootPrefix());
        assertEquals("knowledge/mongodb/x.txt", service.key("/mongodb/x.txt"));
        assertEquals("/knowledge", service.includePath());
    }
}
