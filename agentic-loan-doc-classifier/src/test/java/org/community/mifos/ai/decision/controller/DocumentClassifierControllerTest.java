/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.ai.decision.controller;

import org.community.mifos.ai.decision.dto.DocumentEvaluationRequest;
import org.community.mifos.ai.decision.dto.DocumentEvaluationResponse;
import org.community.mifos.ai.decision.service.BankStatementClassifierService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DocumentClassifierController.class)
class DocumentClassifierControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BankStatementClassifierService classifierService;

    @Test
    @DisplayName("POST /evaluate (JSON)")
    void evaluateJson() throws Exception {
        when(classifierService.evaluate(any(DocumentEvaluationRequest.class)))
                .thenReturn(new DocumentEvaluationResponse(
                        true, 0.91, "bank_account_statement", "clef-flash", "ok"));

        mockMvc.perform(post("/api/v1/documents/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentText\": \"ACCOUNT STATEMENT\\nBalance: 1000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isBankAccountStatement").value(true))
                .andExpect(jsonPath("$.model").value("clef-flash"));
    }

    @Test
    @DisplayName("POST /evaluate-file (multipart image)")
    void evaluateFile() throws Exception {
        when(classifierService.evaluateFiles(any(), eq("APP-42")))
                .thenReturn(new DocumentEvaluationResponse(
                        true, 0.87, "bank_account_statement", "clef-flash", "vision",
                        "scan.png", "image/png"));

        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        MockMultipartFile file = new MockMultipartFile("file", "scan.png", "image/png", png);

        mockMvc.perform(multipart("/api/v1/documents/evaluate-file")
                        .file(file)
                        .param("applicantId", "APP-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceFilename").value("scan.png"))
                .andExpect(jsonPath("$.sourceContentType").value("image/png"));
    }

    @Test
    @DisplayName("Blank text → 400")
    void blankText() throws Exception {
        mockMvc.perform(post("/api/v1/documents/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentText\": \"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Unsupported file → 400")
    void unsupportedFile() throws Exception {
        when(classifierService.evaluateFiles(any(), any()))
                .thenThrow(new IllegalArgumentException(
                        "clef-flash accepts base64 PNG, JPEG or WebP images only"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", new byte[12]);

        mockMvc.perform(multipart("/api/v1/documents/evaluate-file").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("PNG, JPEG or WebP")));
    }
}
