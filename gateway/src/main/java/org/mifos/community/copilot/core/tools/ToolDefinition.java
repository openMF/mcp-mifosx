/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One tool from the reviewed, version-controlled manifest ({@code tools.yaml}).
 *
 * <p>The manifest, not the tool server and not the model, is the enforcement authority for what
 * the LLM may touch and what counts as a write (ADR-001 §04, default-deny).
 */
public record ToolDefinition(String name, String description, boolean write, String summaryTemplate,
        List<Param> params, RestMapping rest, List<String> redactFields, List<Enrich> enrich, Defaults defaults,
        Map<String, String> computed) {

    public ToolDefinition {
        computed = computed == null ? Map.of() : Map.copyOf(computed);
    }

    /**
     * One declared parameter.
     *
     * @param label what the officer reads on the confirmation card, rather than the raw
     *     parameter name; "Approved amount" instead of {@code approvedLoanAmount}
     * @param format {@code money} or {@code date} where the value needs presenting as such
     * @param show false for identifiers, which say nothing to a person and are replaced on the
     *     card by the account number and product name pulled in by {@link Enrich}
     */
    /**
     * One parameter, including what counts as a valid value for it.
     *
     * <p>{@code pattern}, {@code min} and {@code max} are copied from the rules the Mifos web
     * app already enforces on the same field, and are deliberately no stricter. The Copilot
     * sits beside that app rather than behind it, so a value the officer could type into the
     * form has to be a value they can ask for in a sentence. Being stricter here would refuse
     * legitimate work; being looser makes the Copilot a way round the app's own controls,
     * which is how a client came to be created with a fifteen digit number for a name.
     *
     * <p>{@code mustBe} completes the sentence "First name must ...", because a regular
     * expression is not something to show a loan officer.
     */
    public record Param(String name, String type, boolean required, String description, String label, String format,
            boolean show, boolean futureAllowed, String pattern, String mustBe, String min, String max,
            String maxLength) {

        public Param(String name, String type, boolean required, String description, String label, String format,
                boolean show) {
            this(name, type, required, description, label, format, show, false);
        }

        public Param(String name, String type, boolean required, String description, String label, String format,
                boolean show, boolean futureAllowed) {
            this(name, type, required, description, label, format, show, futureAllowed, null, null, null, null,
                    null);
        }

        /** The label if the manifest gave one, otherwise the parameter name as a last resort. */
        public String displayLabel() {
            return label == null || label.isBlank() ? name : label;
        }

        public boolean isMoney() {
            return "money".equalsIgnoreCase(format);
        }

        public boolean isDate() {
            return "date".equalsIgnoreCase(format);
        }

        /**
         * Whether a date after the business date is legitimate for this field.
         *
         * <p>False for almost everything, because Fineract refuses to book a repayment or an
         * approval in its own future. True for a date that is meant to be ahead: an expected
         * disbursement is a plan, and clamping it to today rewrites the whole repayment
         * schedule that Fineract generates from it.
         */
        public boolean allowsFuture() {
            return futureAllowed;
        }
    }

    /**
     * A read performed before a confirmation card is shown, so the card can name the account,
     * the product and the client. Runs with the officer's own credential like any other call.
     *
     * <p>A tool may declare several: approving a new loan means naming both the client and the
     * product, which live behind different endpoints.
     *
     * @param currencyPath dotted path to the currency this record is denominated in, so amounts
     *     on the card read "USD 28,000.00" and not a bare number
     * @param fields card row label to a dotted path in the response; a value prefixed
     *     {@code #money:} is formatted as an amount
     */
    public record Enrich(String path, String currencyPath, Map<String, String> fields) {}

    /**
     * Where a tool's unspecified parameters come from.
     *
     * <p>Fineract requires a loan's interest rate, repayment frequency, amortisation and
     * strategy on every application, and refuses the request without them. They belong to the
     * loan product, so the manifest reads them from it rather than inventing values. Hardcoding
     * an interest rate in a request body silently overrides whatever the institution
     * configured, which is how every loan ends up at the same rate.
     *
     * <p>An officer who names a value keeps it. These only fill what was left unsaid.
     *
     * @param fields parameter name to a dotted path in the response
     */
    public record Defaults(String path, Map<String, String> fields) {}

    /*
     * A tool may also declare `computed:` entries of the form "a * b", naming two other
     * parameters. A loan's term is its number of repayments multiplied by how often it
     * repays, and a manifest token cannot multiply. Aliasing the term to the repayment count
     * is only right while a loan repays every single period; on a fortnightly product it
     * understates the term by half, and Fineract books arrears against the wrong dates.
     */

    /** How the direct-REST executor maps this tool onto the core banking API. */
    public record RestMapping(String method, String path, String bodyTemplate) {}

    /** OpenAI-style function schema handed to the model. */
    public Map<String, Object> toOpenAiSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = params == null ? List.of()
                : params.stream().filter(Param::required).map(Param::name).toList();
        if (params != null) {
            for (Param param : params) {
                properties.put(param.name(), Map.of(
                        "type", param.type() == null ? "string" : param.type(),
                        "description", param.description() == null ? "" : param.description()));
            }
        }
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description == null ? "" : description,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", properties,
                                "required", required,
                                // Advisory for many providers, but it steers the model away from
                                // inventing args; the loop refuses undeclared args regardless.
                                "additionalProperties", false)));
    }

    /** Officer-facing one-liner for the approval card, e.g. "Approve loan #{loanId}". */
    public String humanSummary(Map<String, Object> args) {
        String template = summaryTemplate != null ? summaryTemplate : (description != null ? description : name);
        String out = template;
        if (args != null) {
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                out = out.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }
        }
        return out;
    }
}
