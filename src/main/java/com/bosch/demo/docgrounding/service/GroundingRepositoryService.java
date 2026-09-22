package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Single source of truth for "where does grounding data live".
 *
 * <p>Every writer (Docupedia sync, MongoDB grounding, rollups) builds its S3 keys through
 * {@link #key(String)} so all of them land under one include path, and every reader resolves its
 * repository through {@link #resolveRepositoryId(String)} so all of them query one vector
 * repository. That combination is what makes "one repository for all vector data" actually hold
 * rather than being a convention nobody enforces.</p>
 */
@Service
@Slf4j
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
     * Decide which repository a request should hit.
     *
     * @param requested the repositoryId (or legacy s3Prefix) sent by the caller; may be null
     * @return the repository id to use
     * @throws IllegalStateException when nothing is configured and nothing was supplied
     */
    public String resolveRepositoryId(String requested) {
        String configured = configuredRepositoryId();
        String supplied = requested == null ? "" : requested.trim();

        if (configured.isEmpty()) {
            if (supplied.isEmpty()) {
                throw new IllegalStateException(
                        "No vector repository available. Set app.grounding.repository-id "
                                + "(or GROUNDING_REPOSITORY_ID) to the single repository id.");
            }
            return supplied;
        }

        if (!supplied.isEmpty() && !supplied.equals(configured) && grounding().isEnforceSingleRepository()) {
            log.info("Ignoring requested repositoryId={} - all grounding queries use the single repository {}",
                    supplied, configured);
        }

        if (grounding().isEnforceSingleRepository()) {
            return configured;
        }
        return supplied.isEmpty() ? configured : supplied;
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

    public String configuredPipelineId() {
        String id = grounding().getPipelineId();
        return id == null ? "" : id.trim();
    }
}
