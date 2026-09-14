package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import com.bosch.demo.docgrounding.model.DocupediaPage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocupediaClientService {

    private final WebClient.Builder webClientBuilder;
    private final AppProperties properties;

    /**
     * Returns ALL root pages inside a Docupedia space.
     * Uses start/limit pagination: keeps fetching while size == limit.
     */
    public List<DocupediaPage> getAllPages(String spaceKey) {
        int limit = properties.getDocupedia().getPageSize();
        WebClient webClient = buildWebClient();
        List<DocupediaPage> all = new ArrayList<>();
        int start = 0;

        while (true) {
            int s = start;
            int l = limit;
            Map<String, Object> response =
                    webClient.get()
                            .uri(b -> b.path("/rest/api/space/{key}/content/page")
                                    .queryParam("limit", l)
                                    .queryParam("start", s)
                                    .queryParam("expand", "version")
                                    .build(spaceKey))
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                            .block();

            if (response == null) {
                break;
            }

            List<DocupediaPage> batch = parsePages(response, spaceKey);
            all.addAll(batch);

            int size = extractInt(response, "size");
            log.debug("getAllPages spaceKey={} start={} size={} total={}", spaceKey, start, size, all.size());

            if (size < limit) {
                break;
            }
            start += limit;
        }

        log.info("getAllPages spaceKey={} totalPages={}", spaceKey, all.size());
        return all;
    }

    /**
     * Returns one page metadata and validates it belongs to the given space (when present in response).
     */
    public DocupediaPage getPageById(String pageId, String expectedSpaceKey) {
        Map<String, Object> result =
                buildWebClient().get()
                        .uri("/rest/api/content/{id}?expand=space,version", pageId)
                        .retrieve()
                        .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .block();

        if (result == null) {
            return null;
        }

        String pageSpaceKey = extractSpaceKey(result);
        if (expectedSpaceKey != null
                && !expectedSpaceKey.isBlank()
                && pageSpaceKey != null
                && !pageSpaceKey.isBlank()
                && !expectedSpaceKey.equalsIgnoreCase(pageSpaceKey)) {
            throw new IllegalArgumentException(
                    "Page " + pageId + " belongs to space " + pageSpaceKey + " and not " + expectedSpaceKey);
        }

        String effectiveSpaceKey = (pageSpaceKey == null || pageSpaceKey.isBlank())
                ? expectedSpaceKey
                : pageSpaceKey;

        return toDocupediaPage(result, effectiveSpaceKey == null ? "UNKNOWN" : effectiveSpaceKey);
    }

    /**
     * Returns page HTML content.
     */
    public String getPageContent(String pageId) {
        Map<String, Object> result =
                buildWebClient().get()
                        .uri("/rest/api/content/{id}?expand=body.storage", pageId)
                        .retrieve()
                        .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .block();

        if (result == null) {
            return "";
        }
        Map<String, Object> body = (Map<String, Object>) result.get("body");
        if (body == null) {
            return "";
        }
        Map<String, Object> storage = (Map<String, Object>) body.get("storage");
        if (storage == null) {
            return "";
        }
        return String.valueOf(storage.get("value"));
    }

    /**
     * Returns ALL direct child pages of a page.
     * Uses start/limit pagination: keeps fetching while size == limit.
     */
    public List<DocupediaPage> getChildPages(String pageId) {
        int limit = properties.getDocupedia().getPageSize();
        WebClient webClient = buildWebClient();
        List<DocupediaPage> all = new ArrayList<>();
        int start = 0;

        while (true) {
            int s = start;
            int l = limit;
            Map<String, Object> response =
                    webClient.get()
                            .uri(b -> b.path("/rest/api/content/{id}/child/page")
                                    .queryParam("limit", l)
                                    .queryParam("start", s)
                                    .queryParam("expand", "version")
                                    .build(pageId))
                            .retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                            .block();

            if (response == null) {
                break;
            }

            List<DocupediaPage> batch = parsePages(response, "UNKNOWN");
            all.addAll(batch);

            int size = extractInt(response, "size");
            log.debug("getChildPages pageId={} start={} size={} total={}", pageId, start, size, all.size());

            if (size < limit) {
                break;
            }
            start += limit;
        }

        return all;
    }

    private WebClient buildWebClient() {
        return webClientBuilder
                .baseUrl(properties.getDocupedia().getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + properties.getDocupedia().getBearerToken())
                .build();
    }

    private int extractInt(Map<String, Object> response, String key) {
        Object val = response.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String extractSpaceKey(Map<String, Object> page) {
        Object spaceObj = page.get("space");
        if (!(spaceObj instanceof Map<?, ?> spaceMap)) {
            return null;
        }
        Object keyObj = spaceMap.get("key");
        return keyObj == null ? null : keyObj.toString();
    }

    private List<DocupediaPage> parsePages(Map<String, Object> response, String spaceKey) {
        List<DocupediaPage> pages = new ArrayList<>();
        if (response == null || !(response.get("results") instanceof List<?> results)) {
            return pages;
        }

        for (Object item : results) {
            if (item instanceof Map<?, ?> pageMap) {
                pages.add(toDocupediaPage(pageMap, spaceKey));
            }
        }

        return pages;
    }

    private DocupediaPage toDocupediaPage(Map<?, ?> page, String spaceKey) {
        String id = page.get("id") == null ? "" : page.get("id").toString();
        String title = page.get("title") == null ? "" : page.get("title").toString();

        String webui = "";
        Object links = page.get("_links");
        if (links instanceof Map<?, ?> linkMap) {
            Object webuiObj = linkMap.get("webui");
            if (webuiObj != null) {
                webui = webuiObj.toString();
            }
        }

        String lastModified = extractLastModified(page);

        return new DocupediaPage(
                id,
                title,
                properties.getDocupedia().getBaseUrl() + webui,
                "",
                spaceKey,
                lastModified
        );
    }

    private String extractLastModified(Map<?, ?> page) {
        Object versionObj = page.get("version");
        if (versionObj instanceof Map<?, ?> versionMap) {
            Object when = versionMap.get("when");
            if (when != null) {
                return when.toString();
            }
        }

        return "";
    }
}