package com.bosch.demo.docgrounding.service;

import java.util.ArrayList;
import java.util.List;

import com.bosch.demo.docgrounding.config.AppProperties;
import org.springframework.stereotype.Service;

@Service
public class ChunkingService {
    private final AppProperties properties;

    public ChunkingService(AppProperties properties) {
        this.properties = properties;
    }

    public List<String> chunk(String text) {
        int maxChars = properties.getChunking().getMaxChars();
        int overlap = properties.getChunking().getOverlapChars();
        return chunk(text, maxChars, overlap);
    }

    public List<String> chunk(String text, int maxChars, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be greater than 0");
        }
        if (overlap < 0 || overlap >= maxChars) {
            overlap = Math.max(0, maxChars / 10);
        }

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            if (end < text.length()) {
                int sentenceEnd = Math.max(text.lastIndexOf(". ", end), text.lastIndexOf("\n", end));
                if (sentenceEnd > start + maxChars / 2) {
                    end = sentenceEnd + 1;
                }
            }
            chunks.add(text.substring(start, end).trim());
            if (end == text.length()) {
                break;
            }
            start = Math.max(0, end - overlap);
        }
        return chunks;
    }
}
