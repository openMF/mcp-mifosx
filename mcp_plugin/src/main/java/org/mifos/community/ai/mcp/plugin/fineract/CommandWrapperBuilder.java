// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package org.mifos.community.ai.mcp.plugin.fineract;

/**
 * Minimal stub representing Fineract's CommandWrapperBuilder.
 * <p>
 * In Fineract, this is a fluent builder that constructs a {@code CommandWrapper}
 * describing a write operation. Common builder methods include:
 * <ul>
 *   <li>{@code createClient()}</li>
 *   <li>{@code activateClient(clientId)}</li>
 *   <li>{@code createSavingsProduct()}</li>
 *   <li>{@code approveSavingsAccountApplication(accountId)}</li>
 *   <li>{@code createLoanApplication()}</li>
 *   <li>{@code approveLoanApplication(loanId)}</li>
 *   <li>{@code disburseLoanApplication(loanId)}</li>
 *   <li>{@code createAccountTransfer()}</li>
 *   <li>... and many more</li>
 * </ul>
 * <p>
 * Each builder method sets the entity type, action type, and resource URL.
 * The {@code withJson(String)} method attaches the JSON payload.
 * The {@code build()} method produces the final CommandWrapper.
 * <p>
 * In a real Fineract deployment, this class is provided by {@code fineract-core}.
 * This stub exists for standalone compilation and to document the builder pattern.
 *
 * @see PortfolioCommandSourceWritePlatformService
 * @see <a href="https://github.com/apache/fineract">Apache Fineract source</a>
 */
public class CommandWrapperBuilder {

    private String entityName;
    private String actionName;
    private Long entityId;
    private String jsonPayload;

    public CommandWrapperBuilder createClient() {
        this.actionName = "CREATE";
        this.entityName = "CLIENT";
        return this;
    }

    public CommandWrapperBuilder activateClient(final Long clientId) {
        this.actionName = "ACTIVATE";
        this.entityName = "CLIENT";
        this.entityId = clientId;
        return this;
    }

    public CommandWrapperBuilder createSavingsProduct() {
        this.actionName = "CREATE";
        this.entityName = "SAVINGSPRODUCT";
        return this;
    }

    public CommandWrapperBuilder createSavingsAccount() {
        this.actionName = "CREATE";
        this.entityName = "SAVINGSACCOUNT";
        return this;
    }

    public CommandWrapperBuilder approveSavingsAccountApplication(final Long accountId) {
        this.actionName = "APPROVE";
        this.entityName = "SAVINGSACCOUNT";
        this.entityId = accountId;
        return this;
    }

    public CommandWrapperBuilder savingsAccountActivation(final Long accountId) {
        this.actionName = "ACTIVATE";
        this.entityName = "SAVINGSACCOUNT";
        this.entityId = accountId;
        return this;
    }

    public CommandWrapperBuilder savingsAccountDeposit(final Long accountId) {
        this.actionName = "DEPOSIT";
        this.entityName = "SAVINGSACCOUNT";
        this.entityId = accountId;
        return this;
    }

    public CommandWrapperBuilder savingsAccountWithdrawal(final Long accountId) {
        this.actionName = "WITHDRAWAL";
        this.entityName = "SAVINGSACCOUNT";
        this.entityId = accountId;
        return this;
    }

    public CommandWrapperBuilder createLoanProduct() {
        this.actionName = "CREATE";
        this.entityName = "LOANPRODUCT";
        return this;
    }

    public CommandWrapperBuilder createLoanApplication() {
        this.actionName = "CREATE";
        this.entityName = "LOAN";
        return this;
    }

    public CommandWrapperBuilder approveLoanApplication(final Long loanId) {
        this.actionName = "APPROVE";
        this.entityName = "LOAN";
        this.entityId = loanId;
        return this;
    }

    public CommandWrapperBuilder disburseLoanApplication(final Long loanId) {
        this.actionName = "DISBURSE";
        this.entityName = "LOAN";
        this.entityId = loanId;
        return this;
    }

    public CommandWrapperBuilder loanRepaymentTransaction(final Long loanId) {
        this.actionName = "REPAYMENT";
        this.entityName = "LOAN";
        this.entityId = loanId;
        return this;
    }

    public CommandWrapperBuilder createAccountTransfer() {
        this.actionName = "CREATE";
        this.entityName = "ACCOUNTTRANSFER";
        return this;
    }

    public CommandWrapperBuilder createCharge() {
        this.actionName = "CREATE";
        this.entityName = "CHARGE";
        return this;
    }

    public CommandWrapperBuilder updateCharge(final Long chargeId) {
        this.actionName = "UPDATE";
        this.entityName = "CHARGE";
        this.entityId = chargeId;
        return this;
    }

    public CommandWrapperBuilder withJson(final String json) {
        this.jsonPayload = json;
        return this;
    }

    public Object build() {
        // In real Fineract, this returns a CommandWrapper object.
        // This stub returns a simple representation for compilation.
        return new Object() {
            @Override
            public String toString() {
                return String.format("CommandWrapper[action=%s, entity=%s, entityId=%s]",
                        actionName, entityName, entityId);
            }
        };
    }

    // Getters for test visibility
    public String getEntityName() { return entityName; }
    public String getActionName() { return actionName; }
    public Long getEntityId() { return entityId; }
    public String getJsonPayload() { return jsonPayload; }
}
