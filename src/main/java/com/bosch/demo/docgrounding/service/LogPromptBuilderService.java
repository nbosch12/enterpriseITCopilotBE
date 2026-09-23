package com.bosch.demo.docgrounding.service;

import com.bosch.demo.docgrounding.model.NormalizedLogContext;
import org.springframework.stereotype.Service;

@Service
public class LogPromptBuilderService {

    public String buildPrompt(String userQuestion, NormalizedLogContext normalizedLogContext) {
        StringBuilder builder = new StringBuilder();
        builder.append("You are an enterprise incident analysis assistant. ")
                .append("Use only the provided Azure log summary. ")
                .append("Do not invent causes that are not supported by the logs. ")
                .append("Return JSON only with the following schema:\n")
                .append("{\n")
                .append("  \"title\": string,\n")
                .append("  \"overallSeverity\": \"INFO\" | \"LOW\" | \"MEDIUM\" | \"HIGH\" | \"CRITICAL\",\n")
                .append("  \"summary\": string,\n")
                .append("  \"impactedApps\": [string],\n")
                .append("  \"likelyCauses\": [string],\n")
                .append("  \"recommendedActions\": [string],\n")
                .append("  \"keyObservations\": [string]\n")
                .append("}\n\n")
                .append("User question:\n")
                .append(userQuestion)
                .append("\n\n")
                .append("Normalized log context:\n")
                .append(normalizedLogContext.normalizedText())
                .append("\n\n")
                .append("Additional instructions:\n")
                .append("- Keep the summary concise but actionable.\n")
                .append("- Mention patterns or dominant errors if repeated.\n")
                .append("- Recommend next actions that an operator can take immediately.\n")
                .append("- If there are no matching errors, say that clearly.\n")
                .append("- Output valid JSON only, without markdown fences.\n");

        return builder.toString();
    }
}

