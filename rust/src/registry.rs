// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

use std::collections::{HashMap, HashSet};
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use crate::embeddings::SemanticRouter;

/// Minimum similarity score required for a domain to be considered relevant.
pub const INTENT_ROUTE_THRESHOLD: f32 = 0.20;

/// Request schema for the Hybrid Intent Router tool.
#[derive(Debug, Serialize, Deserialize, JsonSchema)]
pub struct IntentRouterReq {
    /// The natural language banking query to resolve into relevant tools.
    /// Example: "I need to check if a client has any overdue installments"
    pub query: String,
}

/// DomainRegistry provides NLP intent routing for Bank Staff queries.
/// This is used by LLM agents (e.g., LangGraph) that wish to aggressively prune the Context Window.
///
/// Supports two routing modes:
/// - **Keyword** (`route_intent`): Zero-latency string matching (~3us). Always available.
/// - **Semantic** (`route_intent_semantic`): ML-powered cosine similarity (~5-10ms). Requires model init.
#[derive(Clone, Debug)]
pub struct DomainRegistry {
    pub domain_map: HashMap<&'static str, Vec<&'static str>>,
    pub semantic_router: Option<SemanticRouter>,
}

/// Builds the canonical domain -> tool mapping.
/// This is the single source of truth — every tool registered in server.rs must appear here.
fn build_domain_map() -> HashMap<&'static str, Vec<&'static str>> {
    let mut map = HashMap::new();

    map.insert("clients", vec![
        "search_clients_by_name", "get_client_details", "get_client_accounts",
        "create_client", "activate_client", "update_client_mobile", "update_client",
        "delete_client", "close_client",
        "get_client_identifiers", "create_client_identifier",
        "get_client_documents", "get_client_charges",
        "apply_client_charge", "pay_client_charge", "waive_client_charge",
        "get_client_transactions", "get_client_transaction",
        "undo_client_transaction", "get_client_addresses",
        "list_client_collaterals", "get_client_collateral",
        "create_client_collateral", "update_client_collateral", "delete_client_collateral",
    ]);

    map.insert("groups", vec![
        "list_groups", "get_group", "create_group",
        "activate_group", "add_group_member", "remove_group_member",
        "get_group_accounts", "create_group_savings_account",
        "update_group", "close_group",
        "list_centers", "get_center", "create_center",
    ]);

    map.insert("loans", vec![
        "get_loan_details", "get_repayment_schedule", "get_loan_history",
        "get_overdue_loans",
        "create_loan", "update_loan", "delete_loan",
        "list_loan_products", "create_group_loan",
        "approve_and_disburse_loan", "reject_loan_application",
        "make_loan_repayment", "apply_late_fee", "waive_interest",
    ]);

    map.insert("collaterals", vec![
        "list_loan_collaterals", "get_loan_collateral", "create_loan_collateral",
        "update_loan_collateral", "delete_loan_collateral",
    ]);

    map.insert("savings", vec![
        "get_savings_account", "get_savings_transactions", "create_savings_account",
        "list_savings_products",
        "approve_and_activate_savings", "close_savings_account",
        "deposit_savings", "withdraw_savings",
        "apply_savings_charge", "calculate_and_post_interest",
    ]);

    map.insert("staff", vec![
        "list_staff", "get_staff_details", "list_offices", "get_office_details",
    ]);

    map.insert("accounting", vec![
        "list_gl_accounts", "get_journal_entries", "create_journal_entry",
    ]);

    map.insert("bulk", vec![
        "bulk_search_clients", "bulk_get_loan_status", "bulk_disburse_loans",
        "bulk_make_repayments", "bulk_activate_clients", "bulk_get_savings_balances",
        "bulk_apply_fees", "bulk_close_accounts", "bulk_create_savings_accounts",
        "bulk_approve_and_activate_savings", "bulk_deposit_savings",
    ]);

    map.insert("charges", vec![
        "retrieve_charge", "create_charge", "update_charge", "delete_charge",
    ]);

    map
}

impl DomainRegistry {
    /// Full initialization: builds domain map + starts background ML loading.
    pub fn new() -> Self {
        let domain_map = build_domain_map();
        let semantic_router = Some(SemanticRouter::new());

        Self { domain_map, semantic_router }
    }

    /// Create a registry without the semantic router (for benchmarks and tests).
    pub fn new_keywords_only() -> Self {
        Self {
            domain_map: build_domain_map(),
            semantic_router: None,
        }
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

    /// Internal raw keyword matcher. 
    /// Returns an empty Vec if no matches are found (no fallback).
    fn route_keywords_internal(&self, query: &str) -> Vec<&'static str> {
        let mut active_domains = HashSet::new();

        if ["loan", "repayment", "disburse", "overdue", "arrear", "reject", "waive", "installment", "product", "interest", "principal", "emi", "npa", "write-off", "write off", "reschedule", "prepay", "payoff", "amortiz", "moratorium", "grace period"].iter().any(|w| query.contains(w)) {
            active_domains.insert("loans");
        }
        if ["client", "person", "customer", "borrower", "applicant", "search", "activate", "mobile", "kyc", "identifier", "address", "document", "onboard", "enroll", "register"].iter().any(|w| query.contains(w)) {
            active_domains.insert("clients");
        }
        if ["collateral", "security", "pledge", "lien", "asset", "guarantee", "mortgage"].iter().any(|w| query.contains(w)) {
            active_domains.insert("collaterals");
        }
        if ["group", "center", "centre", "member", "lending group", "solidarity", "village banking"].iter().any(|w| query.contains(w)) {
            active_domains.insert("groups");
        }
        if ["saving", "deposit", "withdraw", "balance", "wallet", "fixed deposit", "recurring", "fd ", "rd "].iter().any(|w| query.contains(w)) {
            active_domains.insert("savings");
        }
        if ["staff", "officer", "employee", "office", "branch", "manager", "teller", "cashier", "supervisor"].iter().any(|w| query.contains(w)) {
            active_domains.insert("staff");
        }
        if ["journal", "ledger", "gl account", "gl ", "debit", "credit", "accounting", "general ledger", "chart of accounts", "reconcil", "trial balance", "posting"].iter().any(|w| query.contains(w)) {
            active_domains.insert("accounting");
        }
        if ["bulk", "multiple", "batch", "parallel", "concurrent", "all at once", "mass", "each", "both", "all of them", "everyone", "all clients", "all loans", "all savings"].iter().any(|w| query.contains(w)) {
            active_domains.insert("bulk");
        }
        if ["charge", "fee", "penalty", "tax", "fee definition", "surcharge", "levy", "service charge", "processing fee"].iter().any(|w| query.contains(w)) {
            active_domains.insert("charges");
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

    /// Keyword-based intent routing. Returns "clients" tools by default if no matches.
    pub fn route_intent(&self, user_query: &str) -> Vec<&'static str> {
        let query = user_query.to_lowercase();
        let kw_tools = self.route_keywords_internal(&query);
        
        if kw_tools.is_empty() {
            self.get_domain("clients")
        } else {
            kw_tools
        }
    }

    /// Hybrid intent routing: Optimized for speed and accuracy.
    ///
    /// 1. **Fast Path** (Keyword): Returns immediately if keywords are found.
    /// 2. **Slow Path** (Semantic Rescue): Only triggers if keywords fail (0 matches).
    pub async fn route_intent_hybrid(&self, user_query: &str, threshold: f32) -> Vec<&'static str> {
        let query = user_query.to_lowercase();
        
        // 1. Fast Path: Keyword Match
        let kw_tools = self.route_keywords_internal(&query);
        if !kw_tools.is_empty() {
            return kw_tools;
        }

        // 2. Slow Path: Semantic Rescue (only if keywords failed)
        if let Some(ref router) = self.semantic_router {
            match router.route(user_query, threshold).await {
                Ok(scored_domains) if !scored_domains.is_empty() => {
                    let mut result = Vec::new();
                    let mut seen = HashSet::new();
                    for (domain_name, _score) in scored_domains {
                        if let Some(tools) = self.domain_map.get(domain_name) {
                            for t in tools {
                                if seen.insert(*t) {
                                    result.push(*t);
                                }
                            }
                        }
                    }
                    if !result.is_empty() {
                        return result;
                    }
                }
                _ => {}
            }
        }

        // Final Fallback: Default to "clients"
        self.get_domain("clients")
    }
}
