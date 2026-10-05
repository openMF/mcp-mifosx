/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.ai.decision.service;

import org.community.mifos.ai.decision.dto.DocumentEvaluationRequest;
import org.community.mifos.ai.decision.dto.DocumentEvaluationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.SystemOneRequest;
import org.springaicommunity.typesafe.response.ChoiceAnswer;
import org.springaicommunity.typesafe.response.SystemOneResponse;
import org.springframework.mock.web.MockMultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.community.mifos.ai.decision.client.OllamaSystemOneClient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BankStatementClassifierServiceTest {

    @Mock
    private TypeSafeClient typeSafeClient;

    @Mock
    private OllamaSystemOneClient ollamaSystemOneClient;

    private BankStatementClassifierService service;

    @BeforeEach
    void setUp() {
        service = new BankStatementClassifierService(typeSafeClient, ollamaSystemOneClient, "clef-flash", 0.75);
    }

    @Test
    @DisplayName("Text: high noul → bank statement")
    void textHighProbability() {
        stubTextResponse(0.93, "bank_account_statement", 0.91);
        DocumentEvaluationResponse r = service.evaluate(new DocumentEvaluationRequest(
                "ACCOUNT STATEMENT\nOpening Balance: $1000", "stmt.txt", "APP-1"));
        assertThat(r.isBankAccountStatement()).isTrue();
        assertThat(r.probability()).isEqualTo(0.93);
        assertThat(r.sourceFilename()).isNull();
    }

    @Test
    @DisplayName("Text: low noul → not bank statement")
    void textLowProbability() {
        stubTextResponse(0.12, "payslip", 0.88);
        DocumentEvaluationResponse r = service.evaluate(
                new DocumentEvaluationRequest("PAYSLIP\nGross Pay: $4000"));
        assertThat(r.isBankAccountStatement()).isFalse();
        assertThat(r.decisionLabel()).isEqualTo("payslip");
    }

    @Test
    @DisplayName("PNG image (magic bytes) → vision path with images array")
    void pngImageUsesVisionPath() throws Exception {
        Map<String, Object> raw = visionResponse(0.88, "bank_account_statement", 0.82);
        when(ollamaSystemOneClient.systemOne(any(), any(), any(List.class))).thenReturn(raw);

        // Valid PNG magic
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        MockMultipartFile file = new MockMultipartFile("file", "scan.png", "image/png", png);

        DocumentEvaluationResponse r = service.evaluateFile(file, "APP-99");

        assertThat(r.isBankAccountStatement()).isTrue();
        assertThat(r.probability()).isEqualTo(0.88);
        assertThat(r.sourceFilename()).isEqualTo("scan.png");
        assertThat(r.sourceContentType()).isEqualTo("image/png");
        verify(ollamaSystemOneClient).systemOne(any(), any(), any(List.class));
        verify(typeSafeClient, never()).systemOne(any(SystemOneRequest.class));
    }

    @Test
    @DisplayName("JPEG detected by magic bytes even if content-type is wrong")
    void jpegMagicBytesOverrideWrongContentType() throws Exception {
        Map<String, Object> raw = visionResponse(0.80, "bank_account_statement", 0.75);
        when(ollamaSystemOneClient.systemOne(any(), any(), any(List.class))).thenReturn(raw);

        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.bin", "application/octet-stream", jpeg);

        DocumentEvaluationResponse r = service.evaluateFile(file, null);
        assertThat(r.isBankAccountStatement()).isTrue();
        assertThat(r.sourceContentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("Multiple images sent together in one System One call")
    void multipleImages() throws Exception {
        Map<String, Object> raw = visionResponse(0.90, "bank_account_statement", 0.85);
        when(ollamaSystemOneClient.systemOne(any(), any(), any(List.class))).thenReturn(raw);

        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        MockMultipartFile f1 = new MockMultipartFile("file", "p1.png", "image/png", png);
        MockMultipartFile f2 = new MockMultipartFile("file", "p2.png", "image/png", png);

        DocumentEvaluationResponse r = service.evaluateFiles(List.of(f1, f2), "APP-1");
        assertThat(r.isBankAccountStatement()).isTrue();
        assertThat(r.sourceFilename()).contains("p1.png");
    }

    @Test
    @DisplayName("Text file upload uses text path")
    void textFileUpload() throws Exception {
        stubTextResponse(0.91, "bank_account_statement", 0.90);
        MockMultipartFile file = new MockMultipartFile(
                "file", "stmt.txt", "text/plain", "Account Statement\nBalance: 1000".getBytes());
        DocumentEvaluationResponse r = service.evaluateFile(file, null);
        assertThat(r.isBankAccountStatement()).isTrue();
        assertThat(r.sourceFilename()).isEqualTo("stmt.txt");
        verify(typeSafeClient).systemOne(any(SystemOneRequest.class));
        verify(ollamaSystemOneClient, never()).systemOne(any(), any(), any(List.class));
    }

    @Test
    @DisplayName("PDF is rejected – clef-flash only accepts PNG/JPEG/WebP")
    void pdfRejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        assertThatThrownBy(() -> service.evaluateFile(file, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clef-flash accepts base64 PNG, JPEG or WebP");
    }

    @Test
    @DisplayName("Empty upload rejected")
    void emptyRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);
        assertThatThrownBy(() -> service.evaluateFile(file, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void stubTextResponse(double noul, String choice, double confidence) {
        SystemOneResponse mockResponse = mock(SystemOneResponse.class);
        when(mockResponse.model()).thenReturn("clef-flash");
        when(mockResponse.noulValue("is_bank_account_statement")).thenReturn(noul);
        when(mockResponse.choiceValue("document_type")).thenReturn(choice);
        ChoiceAnswer choiceAnswer = mock(ChoiceAnswer.class);
        when(choiceAnswer.confidence()).thenReturn(confidence);
        when(mockResponse.choice("document_type")).thenReturn(choiceAnswer);
        when(typeSafeClient.systemOne(any(SystemOneRequest.class))).thenReturn(mockResponse);
    }

    private static Map<String, Object> visionResponse(double noul, String choice, double confidence) {
        Map<String, Object> noulAnswer = Map.of("type", "noul", "noul", noul);
        Map<String, Object> choiceAnswer = Map.of(
                "type", "choice", "choice", choice, "confidence", confidence);
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("is_bank_account_statement", noulAnswer);
        answers.put("document_type", choiceAnswer);
        return Map.of("model", "clef-flash", "answers", answers);
    }
}
