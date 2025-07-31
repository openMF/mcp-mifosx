package org.mifos.community.ai.mcp.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Custom deserializer to convert dates represented as arrays
 * in the format [year, month, day] (e.g., [2025, 5, 9]) into a LocalDate object.
 *
 * This deserializer is useful when receiving JSON containing dates in this format
 * and needing to convert it to a LocalDate object for manipulation within Java.
 *
 * Example:
 * JSON:
 * {
 *     "date": [2025, 5, 9]
 * }
 *
 * Will be deserialized to:
 * LocalDate.of(2025, 5, 9);
 *
 * This deserializer handles the conversion of arrays of three integers
 * (year, month, day) into a valid LocalDate object.
 */

public class DateArrayDeserializer extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.START_ARRAY) {
            int year = p.nextIntValue(-1);
            int month = p.nextIntValue(-1);
            int day = p.nextIntValue(-1);
            p.nextToken(); // move past END_ARRAY
            LocalDate.of(year, month, day);

            LocalDate date = LocalDate.of(year, month, day);

            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd MMMM yyyy");
            String formattedDate = date.format(dtf);

            return formattedDate;
        }
        return null;
    }
}
