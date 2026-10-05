/**
 * Copyright since 2026 Mifos Initiative
 */
package org.community.mifos.agentic.loan;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.community.mifos.agentic.loan.document.DocumentImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentImagesTest {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G'};

    @Test
    void rendersFirstPdfPageToPngAndTagsCurp(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("curp_TEST.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdf.toFile());
        }

        List<Map<String, Object>> docs = DocumentImages.prepare(List.of(pdf.toString()), 72f);

        assertEquals(1, docs.size());
        assertEquals("CURP", docs.get(0).get("docType"));
        byte[] image = Base64.getDecoder().decode((String) docs.get(0).get("imageBase64"));
        assertArrayEquals(PNG_SIGNATURE, Arrays.copyOf(image, 4));
    }

    @Test
    void reportsUnreadableAndUnsupportedDocuments(@TempDir Path dir) throws Exception {
        Path txt = dir.resolve("payslip.txt");
        Files.writeString(txt, "not an image");

        List<Map<String, Object>> docs = DocumentImages.prepare(
                List.of(txt.toString(), dir.resolve("missing.pdf").toString()), 72f);

        assertEquals("GENERIC", docs.get(0).get("docType"));
        assertNotNull(docs.get(0).get("error"));
        assertNull(docs.get(0).get("imageBase64"));
        assertNotNull(docs.get(1).get("error"));
    }
}
