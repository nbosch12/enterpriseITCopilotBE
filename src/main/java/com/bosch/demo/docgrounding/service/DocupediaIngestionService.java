package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.model.DocupediaPage;
import com.bosch.demo.docgrounding.model.SyncResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class DocupediaIngestionService {

    private static final int DEFAULT_CHILD_DEPTH_LEVELS = 3;
    private static final int MAX_CHILD_DEPTH_LEVELS = 3;
    private static final String CHECKPOINT_FOLDER = "_sync";

    private final DocupediaClientService docupediaClientService;
    private final HtmlToTextService htmlToTextService;
    private final ChunkingService chunkingService;
    private final S3ObjectStoreService s3ObjectStoreService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DocupediaIngestionService(
            DocupediaClientService docupediaClientService,
            HtmlToTextService htmlToTextService,
            ChunkingService chunkingService,
            S3ObjectStoreService s3ObjectStoreService) {

        this.docupediaClientService = docupediaClientService;
        this.htmlToTextService = htmlToTextService;
        this.chunkingService = chunkingService;
        this.s3ObjectStoreService = s3ObjectStoreService;
    }

    public SyncResult syncSpace(String spaceKey) {
        return syncSpace(spaceKey, DEFAULT_CHILD_DEPTH_LEVELS, null);
    }

    public SyncResult syncSpace(String spaceKey, Integer childDepth) {
        return syncSpace(spaceKey, childDepth, null);
    }

    public SyncResult syncSpace(String spaceKey, Integer childDepth, String rootPageId) {
        return syncSpaceInternal(spaceKey, childDepth, rootPageId, SyncMode.FULL);
    }

    public SyncResult syncSpaceDelta(String spaceKey, Integer childDepth, String rootPageId) {
        return syncSpaceInternal(spaceKey, childDepth, rootPageId, SyncMode.DELTA);
    }

    public SyncResult syncSpace(String spaceKey, Integer childDepth, String rootPageId, String mode) {
        return syncSpaceInternal(spaceKey, childDepth, rootPageId, SyncMode.from(mode));
    }

    private SyncResult syncSpaceInternal(String spaceKey, Integer childDepth, String rootPageId, SyncMode mode) {

        int chunksUploaded = 0;
        int failedPages = 0;
        Set<String> failedPageIds = new HashSet<>();

        String prefix = "docupedia/" + sanitize(spaceKey);

        int effectiveDepth = normalizeChildDepth(childDepth);
        String effectiveRootPageId = normalizeRootPageId(rootPageId);
        List<DocupediaPage> pages = collectPagesWithChildren(spaceKey, effectiveDepth, effectiveRootPageId);
        String checkpointKey = checkpointKey(prefix, effectiveRootPageId);

        SyncCheckpoint checkpoint = mode == SyncMode.DELTA ? readCheckpoint(checkpointKey) : null;

        if (mode == SyncMode.DELTA
                && checkpoint == null
                && hasExistingChunkData(prefix, pages, effectiveRootPageId)) {
            log.info("Delta checkpoint missing but chunk data exists. Bootstrapping checkpoint and skipping re-index.");
            checkpoint = new SyncCheckpoint(toLastModifiedMap(pages));
        }

        List<DocupediaPage> pagesToProcess = mode == SyncMode.DELTA
                ? selectDeltaPages(pages, checkpoint)
                : pages;

        if (mode == SyncMode.DELTA) {
            cleanupRemovedPages(prefix, pages, checkpoint);
        }

        for (DocupediaPage page : pagesToProcess) {

            try {
                // Remove previous chunks for the page before re-uploading updated chunk set.
                s3ObjectStoreService.deletePrefix(pageChunkPrefix(prefix, page.id()));

                // fetch page html
                String html = docupediaClientService.getPageContent(page.id());

                // convert to plain text
                String plainText = htmlToTextService.toPlainText(html);

                if (plainText == null || plainText.isBlank()) {
                    continue;
                }

                // chunk page
                List<String> chunks = chunkingService.chunk(plainText);

                for (int i = 0; i < chunks.size(); i++) {

                    String key = prefix + "/" + sanitize(page.id()) + "/chunk-" + i + ".txt";

                    Map<String, String> metadata = new HashMap<>();

                    metadata.put("source", "docupedia");
                    metadata.put("spaceKey", spaceKey);
                    metadata.put("pageId", page.id());
                    metadata.put("pageTitle", limit(page.title(), 128));
                    metadata.put("sourceUrl", limit(page.url(), 512));
                    metadata.put("chunkIndex", String.valueOf(i));

                    String content = buildChunkContent(page, i, chunks.get(i));

                    s3ObjectStoreService.uploadText(key, content, metadata);

                    chunksUploaded++;
                }

                log.info("Indexed page : {} chunks={}", page.title(), chunks.size());
            } catch (Exception ex) {
                log.error("Failed page {} error={}", page.id(), ex.getMessage());
                failedPages++;
                failedPageIds.add(page.id());
            }
        }

        if (mode == SyncMode.DELTA) {
            writeCheckpoint(checkpointKey, buildCheckpointForNextDelta(pages, checkpoint, failedPageIds));
        }

        log.info("Sync completed mode={} spaceKey={} rootPageId={} depth={} pagesSeen={} pagesIndexed={} chunks={} failed={}",
                mode.name(),
                spaceKey,
                effectiveRootPageId == null ? "ALL" : effectiveRootPageId,
                effectiveDepth,
                pages.size(),
                pagesToProcess.size(),
                chunksUploaded,
                failedPages);

        String checkpointKeyUsed = mode == SyncMode.DELTA ? checkpointKey : "";
        return new SyncResult(
                spaceKey,
                pagesToProcess.size(),
                chunksUploaded,
                "/" + prefix,
                mode.name().toLowerCase(),
                checkpointKeyUsed,
                pageIds(pagesToProcess));
    }

    private List<String> pageIds(List<DocupediaPage> pages) {
        return pages.stream()
                .map(DocupediaPage::id)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    private int normalizeChildDepth(Integer requestedDepth) {

        if (requestedDepth == null) {
            return DEFAULT_CHILD_DEPTH_LEVELS;
        }

        if (requestedDepth < 0) {
            return 0;
        }

        if (requestedDepth > MAX_CHILD_DEPTH_LEVELS) {
            log.info("Requested childDepth={} exceeds max={}. Using max value.", requestedDepth, MAX_CHILD_DEPTH_LEVELS);
            return MAX_CHILD_DEPTH_LEVELS;
        }

        return requestedDepth;
    }

    private String normalizeRootPageId(String rootPageId) {
        if (rootPageId == null) {
            return null;
        }

        String normalized = rootPageId.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private List<DocupediaPage> selectDeltaPages(List<DocupediaPage> currentPages, SyncCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.pageLastModified == null || checkpoint.pageLastModified.isEmpty()) {
            log.info("Delta checkpoint not found. Processing all pages.");
            return currentPages;
        }

        List<DocupediaPage> delta = new ArrayList<>();
        for (DocupediaPage page : currentPages) {
            String previous = checkpoint.pageLastModified.get(page.id());
            if (isChanged(page.lastModified(), previous)) {
                delta.add(page);
            }
        }

        return delta;
    }

    private void cleanupRemovedPages(String prefix, List<DocupediaPage> currentPages, SyncCheckpoint checkpoint) {
        if (checkpoint == null || checkpoint.pageLastModified == null || checkpoint.pageLastModified.isEmpty()) {
            return;
        }

        Set<String> currentPageIds = currentPages.stream()
                .map(DocupediaPage::id)
                .collect(Collectors.toSet());

        int removed = 0;
        for (String oldPageId : checkpoint.pageLastModified.keySet()) {
            if (oldPageId == null || oldPageId.isBlank() || currentPageIds.contains(oldPageId)) {
                continue;
            }

            removed += s3ObjectStoreService.deletePrefix(pageChunkPrefix(prefix, oldPageId));
        }

        if (removed > 0) {
            log.info("Delta cleanup removed stale chunks count={}", removed);
        }
    }

    private String checkpointKey(String prefix, String rootPageId) {
        String rootSuffix = rootPageId == null ? "all" : sanitize(rootPageId);
        return prefix + "/" + CHECKPOINT_FOLDER + "/state-" + rootSuffix + ".json";
    }

    private String pageChunkPrefix(String prefix, String pageId) {
        return prefix + "/" + sanitize(pageId) + "/";
    }

    private boolean hasExistingChunkData(String prefix, List<DocupediaPage> pages, String rootPageId) {
        if (rootPageId == null) {
            List<String> keys = s3ObjectStoreService.listKeys(prefix + "/", 20);
            return keys.stream().anyMatch(key -> !key.contains("/_sync/"));
        }

        for (DocupediaPage page : pages) {
            if (page.id() == null || page.id().isBlank()) {
                continue;
            }

            List<String> keys = s3ObjectStoreService.listKeys(pageChunkPrefix(prefix, page.id()), 1);
            if (!keys.isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private SyncCheckpoint readCheckpoint(String key) {
        try {
            String payload = s3ObjectStoreService.readTextIfExists(key);
            if (payload == null || payload.isBlank()) {
                return null;
            }

            return objectMapper.readValue(payload, SyncCheckpoint.class);
        } catch (Exception ex) {
            log.warn("Failed to read delta checkpoint key={} error={}", key, ex.getMessage());
            return null;
        }
    }

    private void writeCheckpoint(String key, SyncCheckpoint checkpoint) {
        try {
            String payload = objectMapper.writeValueAsString(checkpoint);
            s3ObjectStoreService.uploadText(
                    key,
                    payload,
                    Map.of(
                            "source", "docupedia",
                            "type", "delta-checkpoint"));
        } catch (Exception ex) {
            log.warn("Failed to write delta checkpoint key={} error={}", key, ex.getMessage());
        }
    }

    private SyncCheckpoint buildCheckpointForNextDelta(
            List<DocupediaPage> currentPages,
            SyncCheckpoint previousCheckpoint,
            Set<String> failedPageIds) {

        Map<String, String> next = toLastModifiedMap(currentPages);

        if (failedPageIds == null || failedPageIds.isEmpty()) {
            return new SyncCheckpoint(next);
        }

        Map<String, String> previous = previousCheckpoint == null || previousCheckpoint.pageLastModified == null
                ? Map.of()
                : previousCheckpoint.pageLastModified;

        // Keep failed pages retryable in the next delta run.
        for (String failedPageId : failedPageIds) {
            if (failedPageId == null || failedPageId.isBlank()) {
                continue;
            }

            String previousLastModified = previous.get(failedPageId);
            if (previousLastModified == null || previousLastModified.isBlank()) {
                next.remove(failedPageId);
            } else {
                next.put(failedPageId, previousLastModified);
            }
        }

        return new SyncCheckpoint(next);
    }

    private Map<String, String> toLastModifiedMap(List<DocupediaPage> pages) {
        Map<String, String> map = new HashMap<>();
        for (DocupediaPage page : pages) {
            if (page.id() == null || page.id().isBlank()) {
                continue;
            }
            map.put(page.id(), normalizeLastModified(page.lastModified()));
        }
        return map;
    }

    private boolean isChanged(String currentLastModified, String previousLastModified) {
        if (previousLastModified == null) {
            return true;
        }

        String current = normalizeLastModified(currentLastModified);
        String previous = normalizeLastModified(previousLastModified);

        if (current.isBlank() || previous.isBlank()) {
            // Missing timestamps are treated as changed to avoid stale data.
            return true;
        }

        return !current.equals(previous);
    }

    private String normalizeLastModified(String value) {
        return value == null ? "" : value.trim();
    }

    private List<DocupediaPage> collectPagesWithChildren(String spaceKey, int maxChildDepth, String rootPageId) {

        List<DocupediaPage> seedPages = resolveSeedPages(spaceKey, rootPageId);
        Map<String, DocupediaPage> uniquePages = new LinkedHashMap<>();

        addPagesWithSpaceKey(uniquePages, seedPages, spaceKey);

        List<DocupediaPage> currentLevel = seedPages;

        for (int depth = 1; depth <= maxChildDepth; depth++) {

            List<DocupediaPage> nextLevel = new ArrayList<>();

            for (DocupediaPage parentPage : currentLevel) {

                if (parentPage.id() == null || parentPage.id().isBlank()) {
                    continue;
                }

                try {
                    List<DocupediaPage> childPages = docupediaClientService.getChildPages(parentPage.id());
                    addPagesWithSpaceKey(uniquePages, childPages, spaceKey);
                    nextLevel.addAll(childPages);
                } catch (Exception ex) {
                    log.warn("Failed to fetch child pages for parentId={} error={}", parentPage.id(), ex.getMessage());
                }
            }

            if (nextLevel.isEmpty()) {
                break;
            }

            currentLevel = nextLevel;
        }

        return new ArrayList<>(uniquePages.values());
    }

    private List<DocupediaPage> resolveSeedPages(String spaceKey, String rootPageId) {

        if (rootPageId == null) {
            return docupediaClientService.getAllPages(spaceKey);
        }

        DocupediaPage rootPage = docupediaClientService.getPageById(rootPageId, spaceKey);

        if (rootPage == null || rootPage.id() == null || rootPage.id().isBlank()) {
            log.warn("rootPageId={} not found in spaceKey={}", rootPageId, spaceKey);
            return new ArrayList<>();
        }

        log.info("Using page-specific sync spaceKey={} rootPageId={}", spaceKey, rootPageId);
        return List.of(rootPage);
    }

    private void addPagesWithSpaceKey(
            Map<String, DocupediaPage> uniquePages,
            List<DocupediaPage> pages,
            String spaceKey) {

        for (DocupediaPage page : pages) {

            if (page.id() == null || page.id().isBlank()) {
                continue;
            }

            uniquePages.putIfAbsent(
                    page.id(),
                    new DocupediaPage(
                            page.id(),
                            page.title(),
                            page.url(),
                            page.htmlContent(),
                            spaceKey,
                            page.lastModified())
            );
        }
    }

    private String buildChunkContent(DocupediaPage page, int chunkIndex, String chunkText) {
        return "source: Docupedia\n"
                + "spaceKey: " + page.spaceKey() + "\n"
                + "pageId: " + page.id() + "\n"
                + "pageTitle: " + page.title() + "\n"
                + "sourceUrl: " + page.url() + "\n"
                + "lastModified: " + page.lastModified() + "\n"
                + "chunkIndex: " + chunkIndex + "\n\n"
                + chunkText;
    }

    private String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private enum SyncMode {
        FULL,
        DELTA;

        private static SyncMode from(String mode) {
            if (mode == null || mode.isBlank()) {
                return DELTA;
            }

            if ("full".equalsIgnoreCase(mode)) {
                return FULL;
            }

            return DELTA;
        }
    }

    private static class SyncCheckpoint {
        public Map<String, String> pageLastModified = new HashMap<>();

        public SyncCheckpoint() {
        }

        public SyncCheckpoint(Map<String, String> pageLastModified) {
            this.pageLastModified = pageLastModified == null ? new HashMap<>() : pageLastModified;
        }
    }

}