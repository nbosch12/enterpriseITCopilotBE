package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import org.springframework.stereotype.Service;

/**
 * Single source of truth for "where does exported grounding data live".
 *
 * <p>Every writer on the MongoDB export path builds its object-store keys through
 * {@link #key(String)}, so a re-run overwrites the same objects instead of scattering copies of the
 * same records across per-call folders.</p>
 *
 * <p>Repository selection for <em>retrieval</em> is not handled here: the vector search service
 * resolves that from the request or {@code app.docupedia.default-repository-id}.</p>
 */
@Service
public class GroundingRepositoryService {

    private final AppProperties properties;

    public GroundingRepositoryService(AppProperties properties) {
        this.properties = properties;
    }

    private AppProperties.Grounding grounding() {
        AppProperties.Grounding configured = properties.getGrounding();
        return configured == null ? new AppProperties.Grounding() : configured;
    }

    /** Configured repository id, or empty when none is set. */
    public String configuredRepositoryId() {
        String id = grounding().getRepositoryId();
        return id == null ? "" : id.trim();
    }

    /**
     * Normalised root prefix: either empty, or ending with exactly one slash.
     */
    public String rootPrefix() {
        String prefix = grounding().getRootPrefix();
        if (prefix == null) {
            return "";
        }
        String trimmed = prefix.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "" : trimmed + "/";
    }

    /**
     * Turn a logical path such as {@code mongodb/qrcode/daily/...} into a full S3 key under the root.
     */
    public String key(String relativePath) {
        String relative = relativePath == null ? "" : relativePath;
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        return rootPrefix() + relative;
    }

    /**
     * The one include path a grounding pipeline should be created with so that a single repository
     * covers Docupedia and MongoDB content together.
     */
    public String includePath() {
        String root = rootPrefix();
        return root.isEmpty() ? "/" : "/" + root.substring(0, root.length() - 1);
    }

}
