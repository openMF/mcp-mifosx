/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.community.mifos.ai.decision.util;

import java.util.Locale;
import java.util.Set;

/**
 * Detects image formats supported by Clef / Clef-Flash on Ollama:
 * base64-encoded <strong>PNG, JPEG, or WebP</strong> only
 * (see <a href="https://ollama.com/library/clef-flash">clef-flash</a>).
 */
public final class ImageFormatDetector {

    public static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/webp"
    );

    private ImageFormatDetector() {
    }

    /**
     * Returns a normalized content type (e.g. {@code image/png}) if the bytes
     * look like a supported image, otherwise {@code null}.
     */
    public static String detect(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return "image/png";
        }
        // JPEG: FF D8 FF
        if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
            return "image/jpeg";
        }
        // WebP: RIFF .... WEBP
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    public static boolean isSupportedContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String ct = contentType;
        int semi = ct.indexOf(';');
        if (semi >= 0) {
            ct = ct.substring(0, semi);
        }
        return SUPPORTED_CONTENT_TYPES.contains(ct.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Resolve effective content type: prefer magic bytes, fall back to declared type.
     */
    public static String resolveContentType(byte[] bytes, String declaredContentType) {
        String detected = detect(bytes);
        if (detected != null) {
            return detected;
        }
        if (isSupportedContentType(declaredContentType)) {
            String ct = declaredContentType;
            int semi = ct.indexOf(';');
            if (semi >= 0) {
                ct = ct.substring(0, semi);
            }
            return ct.trim().toLowerCase(Locale.ROOT);
        }
        return null;
    }
}
