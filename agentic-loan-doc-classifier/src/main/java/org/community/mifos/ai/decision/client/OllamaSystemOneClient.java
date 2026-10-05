/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.ai.decision.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for Ollama's System One endpoint ({@code POST /v1/systemone}).
 * <p>
 * Supports Clef / Clef-Flash multimodal input: base64-encoded PNG, JPEG or WebP
 * images in the {@code images} array (scored jointly with {@code state}).
 *
 * @see <a href="https://ollama.com/library/clef-flash">clef-flash</a>
 * @see <a href="https://docs.ollama.com/api/systemone">System One API</a>
 */
@Component
public class OllamaSystemOneClient {

    private final RestClient restClient;
    private final String model;

    public OllamaSystemOneClient(
            @Value("${spring.ai.typesafe.base-url:http://localhost:11434}") String baseUrl,
            @Value("${spring.ai.typesafe.model:clef-flash}") String model) {
        this.model = model;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Call /v1/systemone with optional images (clef-flash / clef vision).
     *
     * @param state      text or structured state (required by the API even when images are present)
     * @param questions  map of question id → question body
     * @param imageBytes zero or more raw image byte arrays (PNG / JPEG / WebP); may be empty
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> systemOne(Object state,
                                         Map<String, Object> questions,
                                         List<byte[]> imageBytes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("state", state);
        body.put("questions", questions);

        if (imageBytes != null && !imageBytes.isEmpty()) {
            List<String> encoded = new ArrayList<>(imageBytes.size());
            for (byte[] img : imageBytes) {
                if (img != null && img.length > 0) {
                    encoded.add(Base64.getEncoder().encodeToString(img));
                }
            }
            if (!encoded.isEmpty()) {
                body.put("images", encoded);
            }
        }

        return restClient.post()
                .uri("/v1/systemone")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /** Convenience overload for a single image. */
    public Map<String, Object> systemOne(Object state,
                                         Map<String, Object> questions,
                                         byte[] singleImage) {
        List<byte[]> list = (singleImage == null || singleImage.length == 0)
                ? List.of()
                : List.of(singleImage);
        return systemOne(state, questions, list);
    }

    public String model() {
        return model;
    }
}
