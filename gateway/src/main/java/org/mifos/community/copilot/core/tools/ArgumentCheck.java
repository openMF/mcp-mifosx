/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Whether the values in a tool call are ones the officer could have typed into the web app.
 *
 * <p>The Copilot is a companion to a form, not a second door into the same database. Every
 * rule here is copied from a validator the Mifos X web app already applies to the same field,
 * so anything it refuses is something the officer could not have done in the UI either, and
 * anything the UI allows still works in a sentence. A client whose first name was
 * {@code 999999999999999} could never have been created through the form; it was created
 * through the Copilot, because the Copilot had no opinion about what a name looks like.
 *
 * <p>Problems come back as sentences meant for a person. They are handed to the model as a
 * tool result rather than raised as an error, so the assistant can say what is wrong and ask
 * for a better value, which is what a colleague would do.
 */
public final class ArgumentCheck {

    private ArgumentCheck() {
    }

    /** Human-readable problems with these arguments, empty when there is nothing wrong. */
    public static List<String> problems(ToolDefinition tool, Map<String, Object> args) {
        List<String> problems = new ArrayList<>();
        for (ToolDefinition.Param param : tool.params()) {
            Object raw = args == null ? null : args.get(param.name());
            String value = raw == null ? "" : String.valueOf(raw).trim();
            if (value.isEmpty()) {
                if (param.required()) {
                    problems.add(param.displayLabel() + " is needed.");
                }
                continue; // Nothing to check against, and an absent optional is fine.
            }
            checkPattern(param, value, problems);
            checkBounds(param, value, problems);
            checkLength(param, value, problems);
        }
        return problems;
    }

    private static void checkPattern(ToolDefinition.Param param, String value, List<String> problems) {
        if (param.pattern() == null || param.pattern().isBlank()) {
            return;
        }
        // A number reaches us as whatever the model's JSON produced, so 1.2E7 and 12000000 are
        // the same value written two ways. The rule is about the value, not the notation.
        String subject = plain(value);
        try {
            if (!Pattern.compile(param.pattern()).matcher(subject).matches()) {
                problems.add(param.displayLabel() + " must "
                        + (param.mustBe() == null || param.mustBe().isBlank()
                                ? "be in the format this field expects"
                                : param.mustBe())
                        + ".");
            }
        } catch (PatternSyntaxException e) {
            // A broken pattern is a manifest bug. Refusing every value because of it would
            // take the tool out of service, which is worse than not checking this one rule.
        }
    }

    /**
     * How long the column is, which is Fineract's rule rather than the form's.
     *
     * <p>The web app puts no length limit on a name; Fineract stores it in a varchar(50) and
     * refuses anything longer. Left unchecked, a long name reaches the officer as a database
     * complaint after they have already confirmed the card. Better to say it before.
     */
    private static void checkLength(ToolDefinition.Param param, String value, List<String> problems) {
        BigDecimal limit = number(param.maxLength());
        if (limit != null && value.length() > limit.intValue()) {
            problems.add(param.displayLabel() + " must be " + limit.intValue() + " characters or fewer.");
        }
    }

    private static void checkBounds(ToolDefinition.Param param, String value, List<String> problems) {
        BigDecimal number = number(value);
        BigDecimal min = number(param.min());
        BigDecimal max = number(param.max());
        if (number == null) {
            // Declaring a bound is declaring the field numeric. Letting "none" through because
            // it cannot be compared meant a card was raised for a loan of "none" repayments,
            // and the officer learned it was nonsense only when Fineract said so.
            if (min != null || max != null) {
                problems.add(param.displayLabel() + " must be a number.");
            }
            return;
        }
        if (min != null && number.compareTo(min) < 0) {
            problems.add(param.displayLabel() + " must be at least " + min.toPlainString() + ".");
        }
        if (max != null && number.compareTo(max) > 0) {
            problems.add(param.displayLabel() + " must be no more than " + max.toPlainString() + ".");
        }
    }

    /** The value written out in full, so scientific notation does not dodge a pattern. */
    private static String plain(String value) {
        BigDecimal number = number(value);
        return number == null ? value : number.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal number(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
