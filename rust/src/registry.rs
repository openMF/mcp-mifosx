// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

use std::collections::{HashMap, HashSet};

/// DomainRegistry provides NLP intent routing mimicking `registry.py`.
/// This is used by LLM agents (e.g., LangGraph) that wish to aggressively prune the Context Window.
pub struct DomainRegistry {
    pub domain_map: HashMap<&'static str, Vec<&'static str>>,
}

impl DomainRegistry {
    pub fn new() -> Self {
        let mut map = HashMap::new();
        map.insert("clients", vec![
            "search_clients_by_name", "get_client_details", "get_client_accounts",
            "create_client", "activate_client", "update_client_mobile", "close_client",
            "create_group", "get_group_details", "get_client_identifiers",
            "create_client_identifier", "get_client_documents", "get_client_charges",
            "apply_client_charge", "pay_client_charge", "waive_client_charge",
            "get_client_transactions", "get_client_transaction", 
            "undo_client_transaction", "get_client_addresses"
        ]);
        map.insert("groups", vec![
            "list_groups", "activate_group", "add_group_member",
            "remove_group_member", "get_group_accounts", "create_group_savings_account",
            "update_group", "close_group", "list_centers", "get_center", "create_center"
        ]);
        map.insert("loans", vec![
            "get_loan_details", "get_repayment_schedule", "create_loan",
            "approve_and_disburse_loan", "reject_loan_application",
            "make_loan_repayment", "apply_late_fee", "waive_interest",
            "get_overdue_loans", "create_group_loan", "list_loan_products"
        ]);
        map.insert("collaterals", vec![
            "list_loan_collaterals", "get_loan_collateral", "create_loan_collateral",
            "update_loan_collateral", "delete_loan_collateral",
            "list_client_collaterals", "get_client_collateral", "create_client_collateral",
            "update_client_collateral", "delete_client_collateral"
        ]);
        map.insert("savings", vec![
            "get_savings_account", "get_savings_transactions", "create_savings_account",
            "approve_and_activate_savings", "close_savings_account", "deposit_savings",
            "withdraw_savings", "apply_savings_charge", "calculate_and_post_interest", "list_savings_products"
        ]);
        map.insert("staff", vec![
            "list_staff", "get_staff_details", "list_offices", "get_office_details"
        ]);
        map.insert("accounting", vec![
            "list_gl_accounts", "get_journal_entries", "create_journal_entry"
        ]);
        map.insert("bulk", vec![
            "bulk_search_clients", "bulk_get_loan_status", "bulk_disburse_loans",
            "bulk_make_repayments", "bulk_activate_clients", "bulk_get_savings_balances",
            "bulk_apply_fees", "bulk_close_accounts", "bulk_create_savings_accounts",
            "bulk_approve_and_activate_savings", "bulk_deposit_savings"
        ]);
        map.insert("charges", vec![
            "retrieve_charge", "create_charge", "update_charge", "delete_charge"
        ]);

        Self { domain_map: map }
    }

    pub fn get_domain(&self, domain: &str) -> Vec<&'static str> {
        self.domain_map.get(domain).cloned().unwrap_or_default()
    }

    pub fn get_all_tools(&self) -> Vec<&'static str> {
        let mut tools = Vec::new();
        let mut seen = HashSet::new();
        for list in self.domain_map.values() {
            for t in list {
                if seen.insert(*t) {
                    tools.push(*t);
                }
            }
        }
        tools
    }

    /// Analyzes a user query and returns tool names required.
    pub fn route_intent(&self, user_query: &str) -> Vec<&'static str> {
        let query = user_query.to_lowercase();
        let mut active_domains = HashSet::new();

        if ["loan", "repayment", "disburse", "overdue", "arrear", "reject", "waive", "installment", "product"].iter().any(|w| query.contains(w)) {
            active_domains.insert("loans");
        }
        if ["client", "person", "search", "activate", "mobile", "kyc", "identifier", "address", "charge", "fee", "document"].iter().any(|w| query.contains(w)) {
            active_domains.insert("clients");
        }
        if ["collateral", "security", "pledge"].iter().any(|w| query.contains(w)) {
            active_domains.insert("collaterals");
        }
        if ["group", "center", "centre", "member", "lending group"].iter().any(|w| query.contains(w)) {
            active_domains.insert("groups");
        }
        if ["saving", "deposit", "withdraw", "balance", "wallet", "interest", "product"].iter().any(|w| query.contains(w)) {
            active_domains.insert("savings");
        }
        if ["staff", "officer", "employee", "office", "branch"].iter().any(|w| query.contains(w)) {
            active_domains.insert("staff");
        }
        if ["journal", "ledger", "account", "gl account", "debit", "credit", "accounting"].iter().any(|w| query.contains(w)) {
            active_domains.insert("accounting");
        }
        if ["bulk", "multiple", "batch", "parallel", "concurrent", "all at once", "mass", "each", "both", "all of them"].iter().any(|w| query.contains(w)) {
            active_domains.insert("bulk");
        }
        if ["charge", "fee", "penalty", "tax", "fee definition"].iter().any(|w| query.contains(w)) {
            active_domains.insert("charges");
        }

        if active_domains.is_empty() {
            active_domains.insert("clients");
        }

        let mut result = Vec::new();
        let mut seen = HashSet::new();
        for d in active_domains {
            if let Some(tools) = self.domain_map.get(d) {
                for t in tools {
                    if seen.insert(*t) {
                        result.push(*t);
                    }
                }
            }
        }
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_route_intent_loans() {
        let registry = DomainRegistry::new();
        let tools = registry.route_intent("I want to apply for a loan");
        assert!(tools.contains(&"create_loan"));
        assert!(tools.contains(&"get_loan_details"));
    }

    #[test]
    fn test_route_intent_savings() {
        let registry = DomainRegistry::new();
        let tools = registry.route_intent("deposit money into my savings");
        assert!(tools.contains(&"deposit_savings"));
        assert!(tools.contains(&"get_savings_account"));
    }

    #[test]
    fn test_route_intent_bulk() {
        let registry = DomainRegistry::new();
        let tools = registry.route_intent("bulk search for Matt and Syn");
        assert!(tools.contains(&"bulk_search_clients"));
    }

    #[test]
    fn test_route_intent_default_clients() {
        let registry = DomainRegistry::new();
        let tools = registry.route_intent("hello world");
        assert!(tools.contains(&"search_clients_by_name"));
    }
}
