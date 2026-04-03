// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

use criterion::{black_box, criterion_group, criterion_main, Criterion};

use mcp_rust_mifosx::registry::DomainRegistry;
use mcp_rust_mifosx::domains::clients;
use mcp_rust_mifosx::domains::loans;

fn bench_registry_new(c: &mut Criterion) {
    c.bench_function("DomainRegistry::new", |b| {
        b.iter(|| black_box(DomainRegistry::new()))
    });
}

fn bench_route_intent_single_domain(c: &mut Criterion) {
    let registry = DomainRegistry::new();
    c.bench_function("route_intent (single domain: loans)", |b| {
        b.iter(|| black_box(registry.route_intent("I want to apply for a loan")))
    });
}

fn bench_route_intent_multi_domain(c: &mut Criterion) {
    let registry = DomainRegistry::new();
    c.bench_function("route_intent (multi domain: loans+savings+clients)", |b| {
        b.iter(|| {
            black_box(registry.route_intent(
                "Search for client John, check his loan repayment and savings deposit history",
            ))
        })
    });
}

fn bench_route_intent_bulk(c: &mut Criterion) {
    let registry = DomainRegistry::new();
    c.bench_function("route_intent (bulk domain)", |b| {
        b.iter(|| black_box(registry.route_intent("bulk activate all pending clients")))
    });
}

fn bench_route_intent_fallback(c: &mut Criterion) {
    let registry = DomainRegistry::new();
    c.bench_function("route_intent (fallback to clients)", |b| {
        b.iter(|| black_box(registry.route_intent("hello world, how are you today?")))
    });
}

fn bench_get_all_tools(c: &mut Criterion) {
    let registry = DomainRegistry::new();
    c.bench_function("get_all_tools (flatten + dedup)", |b| {
        b.iter(|| black_box(registry.get_all_tools()))
    });
}

fn bench_get_domain(c: &mut Criterion) {
    let registry = DomainRegistry::new();
    c.bench_function("get_domain (loans)", |b| {
        b.iter(|| black_box(registry.get_domain("loans")))
    });
}

fn bench_today(c: &mut Criterion) {
    c.bench_function("clients::today (chrono formatting)", |b| {
        b.iter(|| black_box(clients::today()))
    });
}

fn bench_to_result_small(c: &mut Criterion) {
    let val = serde_json::json!({"id": 1, "name": "Test Client", "status": "active"});
    c.bench_function("clients::to_result (small payload)", |b| {
        b.iter(|| black_box(clients::to_result(val.clone())))
    });
}

fn bench_to_result_large(c: &mut Criterion) {
    let val = serde_json::json!({
        "id": 42,
        "accountNo": "000000042",
        "loanProductName": "Short Term Loan",
        "status": {"id": 300, "code": "loanStatusType.active", "value": "Active"},
        "loanType": {"id": 1, "code": "loanType.individual", "value": "Individual"},
        "principal": 10000.0,
        "approvedPrincipal": 10000.0,
        "annualInterestRate": 5.0,
        "numberOfRepayments": 12,
        "repaymentEvery": 1,
        "repaymentFrequencyType": {"id": 2, "code": "repaymentFrequency.periodFrequencyType.months", "value": "Months"},
        "summary": {
            "principalPaid": 5000.0,
            "principalOutstanding": 5000.0,
            "interestPaid": 250.0,
            "interestOutstanding": 250.0,
            "totalOutstanding": 5250.0,
            "totalOverdue": 0.0
        },
        "timeline": {
            "submittedOnDate": [2026, 1, 15],
            "approvedOnDate": [2026, 1, 16],
            "actualDisbursementDate": [2026, 1, 17],
            "expectedMaturityDate": [2027, 1, 17]
        }
    });
    c.bench_function("clients::to_result (large Fineract payload)", |b| {
        b.iter(|| black_box(clients::to_result(val.clone())))
    });
}

fn bench_to_err(c: &mut Criterion) {
    c.bench_function("clients::to_err (anyhow → McpError)", |b| {
        b.iter(|| {
            let err = anyhow::anyhow!("Fineract Error: Loan not found");
            black_box(clients::to_err(err))
        })
    });
}

fn bench_loans_today(c: &mut Criterion) {
    c.bench_function("loans::today (chrono formatting)", |b| {
        b.iter(|| black_box(loans::today()))
    });
}

criterion_group!(
    registry_benches,
    bench_registry_new,
    bench_route_intent_single_domain,
    bench_route_intent_multi_domain,
    bench_route_intent_bulk,
    bench_route_intent_fallback,
    bench_get_all_tools,
    bench_get_domain,
);

criterion_group!(
    domain_helper_benches,
    bench_today,
    bench_to_result_small,
    bench_to_result_large,
    bench_to_err,
    bench_loans_today,
);

criterion_main!(registry_benches, domain_helper_benches);
