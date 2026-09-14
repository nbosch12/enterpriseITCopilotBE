package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ChunkingServiceTest {
    @Test
    void shouldChunkText() {
        AppProperties properties = new AppProperties();
        properties.getChunking().setMaxChars(20);
        properties.getChunking().setOverlapChars(5);
        ChunkingService service = new ChunkingService(properties);
        Assertions.assertTrue(service.chunk("This is one sentence. This is another sentence.").size() >= 2);
    }
}
