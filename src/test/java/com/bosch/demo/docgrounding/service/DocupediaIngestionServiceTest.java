package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.model.DocupediaPage;
import com.bosch.demo.docgrounding.model.SyncResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocupediaIngestionServiceTest {

    private DocupediaIngestionService createService(
            DocupediaClientService clientService,
            HtmlToTextService htmlToTextService,
            ChunkingService chunkingService,
            S3ObjectStoreService s3ObjectStoreService) {
        return new DocupediaIngestionService(
                clientService,
                htmlToTextService,
                chunkingService,
                s3ObjectStoreService
        );
    }

    @Test
    void syncSpace_includesChildrenUpToThreeLevels() {
        DocupediaClientService clientService = mock(DocupediaClientService.class);
        HtmlToTextService htmlToTextService = mock(HtmlToTextService.class);
        ChunkingService chunkingService = mock(ChunkingService.class);
        S3ObjectStoreService s3ObjectStoreService = mock(S3ObjectStoreService.class);

        DocupediaIngestionService ingestionService = createService(
                clientService,
                htmlToTextService,
                chunkingService,
                s3ObjectStoreService);

        // 4-level hierarchy: root -> child -> grand -> great
        DocupediaPage root       = new DocupediaPage("root",  "Root",  "u1", "", "BTMEA", "");
        DocupediaPage child      = new DocupediaPage("child", "Child", "u2", "", "UNKNOWN", "");
        DocupediaPage grandChild = new DocupediaPage("grand", "Grand", "u3", "", "UNKNOWN", "");
        DocupediaPage greatGrand = new DocupediaPage("great", "Great", "u4", "", "UNKNOWN", "");
        // 4th level beyond great — should NOT be fetched (exceeds depth 3)
        DocupediaPage level4     = new DocupediaPage("l4",    "Level4","u5", "", "UNKNOWN", "");

        when(clientService.getAllPages("BTMEA")).thenReturn(List.of(root));
        when(clientService.getChildPages("root")).thenReturn(List.of(child));
        when(clientService.getChildPages("child")).thenReturn(List.of(grandChild));
        when(clientService.getChildPages("grand")).thenReturn(List.of(greatGrand));
        when(clientService.getChildPages("great")).thenReturn(List.of(level4));

        when(clientService.getPageContent(anyString())).thenReturn("<p>Hello</p>");
        when(htmlToTextService.toPlainText("<p>Hello</p>")).thenReturn("hello");
        when(chunkingService.chunk("hello")).thenReturn(Collections.singletonList("chunk-1"));

        SyncResult result = ingestionService.syncSpace("BTMEA");

        // root + child + grand + great = 4 pages; level4 is beyond depth 3, so excluded
        assertEquals(4, result.pagesProcessed());
        assertEquals(4, result.chunksUploaded());
        verify(clientService, times(1)).getChildPages("root");
        verify(clientService, times(1)).getChildPages("child");
        verify(clientService, times(1)).getChildPages("grand");
        verify(clientService, never()).getChildPages("great");
        verify(clientService, never()).getPageContent("l4");
        verify(s3ObjectStoreService, times(4)).uploadText(anyString(), anyString(), anyMap());
    }

    @Test
    void syncSpace_withZeroChildDepth_processesOnlyRootPages() {
        DocupediaClientService clientService = mock(DocupediaClientService.class);
        HtmlToTextService htmlToTextService = mock(HtmlToTextService.class);
        ChunkingService chunkingService = mock(ChunkingService.class);
        S3ObjectStoreService s3ObjectStoreService = mock(S3ObjectStoreService.class);

        DocupediaIngestionService ingestionService = createService(
                clientService,
                htmlToTextService,
                chunkingService,
                s3ObjectStoreService);

        DocupediaPage root = new DocupediaPage("root", "Root", "u1", "", "BTMEA", "");

        when(clientService.getAllPages("BTMEA")).thenReturn(List.of(root));
        when(clientService.getPageContent("root")).thenReturn("<p>Hello</p>");
        when(htmlToTextService.toPlainText("<p>Hello</p>")).thenReturn("hello");
        when(chunkingService.chunk("hello")).thenReturn(Collections.singletonList("chunk-1"));

        SyncResult result = ingestionService.syncSpace("BTMEA", 0);

        assertEquals(1, result.pagesProcessed());
        assertEquals(1, result.chunksUploaded());
        verify(clientService, never()).getChildPages(anyString());
        verify(s3ObjectStoreService, times(1)).uploadText(anyString(), anyString(), anyMap());
    }

    @Test
    void syncSpace_withRootPageId_processesOnlyThatSubtree() {
        DocupediaClientService clientService = mock(DocupediaClientService.class);
        HtmlToTextService htmlToTextService = mock(HtmlToTextService.class);
        ChunkingService chunkingService = mock(ChunkingService.class);
        S3ObjectStoreService s3ObjectStoreService = mock(S3ObjectStoreService.class);

        DocupediaIngestionService ingestionService = createService(
                clientService,
                htmlToTextService,
                chunkingService,
                s3ObjectStoreService);

        DocupediaPage rootPage  = new DocupediaPage("7452462128", "Selected Root", "uRoot", "", "BTMEA", "");
        DocupediaPage child      = new DocupediaPage("child", "Child", "u2", "", "UNKNOWN", "");
        DocupediaPage grand      = new DocupediaPage("grand", "Grand", "u3", "", "UNKNOWN", "");

        when(clientService.getPageById("7452462128", "BTMEA")).thenReturn(rootPage);
        when(clientService.getChildPages("7452462128")).thenReturn(List.of(child));
        when(clientService.getChildPages("child")).thenReturn(List.of(grand));
        when(clientService.getChildPages("grand")).thenReturn(List.of());

        when(clientService.getPageContent(anyString())).thenReturn("<p>Hello</p>");
        when(htmlToTextService.toPlainText("<p>Hello</p>")).thenReturn("hello");
        when(chunkingService.chunk("hello")).thenReturn(Collections.singletonList("chunk-1"));

        SyncResult result = ingestionService.syncSpace("BTMEA", 3, "7452462128");

        assertEquals(3, result.pagesProcessed());
        assertEquals(3, result.chunksUploaded());
        verify(clientService, never()).getAllPages("BTMEA");
        verify(clientService, times(1)).getPageById("7452462128", "BTMEA");
        verify(clientService, times(1)).getChildPages("7452462128");
        verify(s3ObjectStoreService, times(3)).uploadText(anyString(), anyString(), anyMap());
    }

    @Test
    void syncSpaceDelta_processesOnlyChangedPages() {
        DocupediaClientService clientService = mock(DocupediaClientService.class);
        HtmlToTextService htmlToTextService = mock(HtmlToTextService.class);
        ChunkingService chunkingService = mock(ChunkingService.class);
        S3ObjectStoreService s3ObjectStoreService = mock(S3ObjectStoreService.class);

        DocupediaIngestionService ingestionService = createService(
                clientService,
                htmlToTextService,
                chunkingService,
                s3ObjectStoreService);

        DocupediaPage unchanged = new DocupediaPage("root", "Root", "u1", "", "BTMEA", "2026-09-01T10:00:00.000Z");
        DocupediaPage changed = new DocupediaPage("child", "Child", "u2", "", "BTMEA", "2026-09-01T11:00:00.000Z");

        when(clientService.getAllPages("BTMEA")).thenReturn(List.of(unchanged, changed));
        when(s3ObjectStoreService.readTextIfExists("docupedia/BTMEA/_sync/state-all.json"))
                .thenReturn("{\"pageLastModified\":{\"root\":\"2026-09-01T10:00:00.000Z\",\"child\":\"2026-09-01T09:00:00.000Z\"}}");
        when(clientService.getPageContent("child")).thenReturn("<p>delta</p>");
        when(htmlToTextService.toPlainText("<p>delta</p>")).thenReturn("delta");
        when(chunkingService.chunk("delta")).thenReturn(Collections.singletonList("chunk-1"));

        SyncResult result = ingestionService.syncSpaceDelta("BTMEA", 0, null);

        assertEquals(1, result.pagesProcessed());
        assertEquals(1, result.chunksUploaded());
        assertEquals("delta", result.mode());
        assertEquals("docupedia/BTMEA/_sync/state-all.json", result.checkpointKeyUsed());
        assertEquals(List.of("child"), result.selectedPageIds());
        verify(clientService, never()).getPageContent("root");
        verify(clientService, times(1)).getPageContent("child");
        verify(s3ObjectStoreService, times(1)).deletePrefix("docupedia/BTMEA/child/");
    }

    @Test
    void syncSpaceDelta_removesDeletedPagesFromS3() {
        DocupediaClientService clientService = mock(DocupediaClientService.class);
        HtmlToTextService htmlToTextService = mock(HtmlToTextService.class);
        ChunkingService chunkingService = mock(ChunkingService.class);
        S3ObjectStoreService s3ObjectStoreService = mock(S3ObjectStoreService.class);

        DocupediaIngestionService ingestionService = createService(
                clientService,
                htmlToTextService,
                chunkingService,
                s3ObjectStoreService);

        DocupediaPage current = new DocupediaPage("root", "Root", "u1", "", "BTMEA", "2026-09-01T10:00:00.000Z");

        when(clientService.getAllPages("BTMEA")).thenReturn(List.of(current));
        when(s3ObjectStoreService.readTextIfExists("docupedia/BTMEA/_sync/state-all.json"))
                .thenReturn("{\"pageLastModified\":{\"root\":\"2026-09-01T10:00:00.000Z\",\"old\":\"2026-09-01T08:00:00.000Z\"}}");

        SyncResult result = ingestionService.syncSpaceDelta("BTMEA", 0, null);

        assertEquals(0, result.pagesProcessed());
        assertEquals(0, result.chunksUploaded());
        assertEquals("delta", result.mode());
        assertEquals("docupedia/BTMEA/_sync/state-all.json", result.checkpointKeyUsed());
        assertEquals(List.of(), result.selectedPageIds());
        verify(s3ObjectStoreService, times(1)).deletePrefix("docupedia/BTMEA/old/");
        verify(clientService, never()).getPageContent(anyString());
    }

    @Test
    void syncSpaceDelta_writesCheckpointOnPartialFailureAndKeepsFailedPageRetryable() {
        DocupediaClientService clientService = mock(DocupediaClientService.class);
        HtmlToTextService htmlToTextService = mock(HtmlToTextService.class);
        ChunkingService chunkingService = mock(ChunkingService.class);
        S3ObjectStoreService s3ObjectStoreService = mock(S3ObjectStoreService.class);

        DocupediaIngestionService ingestionService = createService(
                clientService,
                htmlToTextService,
                chunkingService,
                s3ObjectStoreService);

        DocupediaPage unchanged = new DocupediaPage("root", "Root", "u1", "", "BTMEA", "2026-09-01T10:00:00.000Z");
        DocupediaPage changed = new DocupediaPage("child", "Child", "u2", "", "BTMEA", "2026-09-01T11:00:00.000Z");

        when(clientService.getAllPages("BTMEA")).thenReturn(List.of(unchanged, changed));
        when(s3ObjectStoreService.readTextIfExists("docupedia/BTMEA/_sync/state-all.json"))
                .thenReturn("{\"pageLastModified\":{\"root\":\"2026-09-01T10:00:00.000Z\",\"child\":\"2026-09-01T09:00:00.000Z\"}}");
        when(clientService.getPageContent("child")).thenThrow(new RuntimeException("temporary fetch failure"));

        SyncResult result = ingestionService.syncSpaceDelta("BTMEA", 0, null);

        assertEquals(1, result.pagesProcessed());
        assertEquals(0, result.chunksUploaded());

        ArgumentCaptor<String> checkpointPayload = ArgumentCaptor.forClass(String.class);
        verify(s3ObjectStoreService, times(1)).uploadText(
                eq("docupedia/BTMEA/_sync/state-all.json"),
                checkpointPayload.capture(),
                anyMap());

        String payload = checkpointPayload.getValue();
        assertTrue(payload.contains("\"root\":\"2026-09-01T10:00:00.000Z\""));
        assertTrue(payload.contains("\"child\":\"2026-09-01T09:00:00.000Z\""));
        assertTrue(!payload.contains("\"child\":\"2026-09-01T11:00:00.000Z\""));
    }

    @Test
    void syncSpaceDelta_bootstrapsCheckpointAndSkipsWhenChunksAlreadyExistForRootScope() {
        DocupediaClientService clientService = mock(DocupediaClientService.class);
        HtmlToTextService htmlToTextService = mock(HtmlToTextService.class);
        ChunkingService chunkingService = mock(ChunkingService.class);
        S3ObjectStoreService s3ObjectStoreService = mock(S3ObjectStoreService.class);

        DocupediaIngestionService ingestionService = createService(
                clientService,
                htmlToTextService,
                chunkingService,
                s3ObjectStoreService);

        DocupediaPage root = new DocupediaPage("7452462128", "Selected Root", "uRoot", "", "BTMEA", "2026-09-08T10:00:00.000Z");
        DocupediaPage child = new DocupediaPage("7452462186", "Child", "uChild", "", "BTMEA", "2026-09-08T10:30:00.000Z");

        when(clientService.getPageById("7452462128", "BTMEA")).thenReturn(root);
        when(clientService.getChildPages("7452462128")).thenReturn(List.of(child));
        when(clientService.getChildPages("7452462186")).thenReturn(List.of());
        when(s3ObjectStoreService.readTextIfExists("docupedia/BTMEA/_sync/state-7452462128.json")).thenReturn(null);
        when(s3ObjectStoreService.listKeys("docupedia/BTMEA/7452462128/", 1)).thenReturn(List.of("docupedia/BTMEA/7452462128/chunk-0.txt"));

        SyncResult result = ingestionService.syncSpaceDelta("BTMEA", 3, "7452462128");

        assertEquals(0, result.pagesProcessed());
        assertEquals(0, result.chunksUploaded());
        assertEquals("delta", result.mode());
        assertEquals("docupedia/BTMEA/_sync/state-7452462128.json", result.checkpointKeyUsed());
        assertEquals(List.of(), result.selectedPageIds());

        verify(s3ObjectStoreService, times(1)).uploadText(
                eq("docupedia/BTMEA/_sync/state-7452462128.json"),
                anyString(),
                anyMap());
        verify(clientService, never()).getPageContent(anyString());
    }
}
