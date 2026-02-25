# Go CLI — Proof of Work

**Binary:** `Banking_Agent/cli/mifos`  
**FastAPI server:** running on `http://localhost:8000`  
**Fineract + DB containers:** both up (4 hours)

---

## Test Results

````carousel
### ✅ Test 1 — Help & Command Discovery
```
$ ./mifos --help

Available Commands:
  clients     👥 Manage clients and groups
  groups      🏢 Manage lending groups
  loans       💰 Manage loans
  route       🔀 Ask the AI router which tools to load for a given intent
  savings     🏦 Manage savings accounts
```
All 5 command groups registered. `--server` flag and `MIFOS_SERVER` env var shown.

<!-- slide -->
### ✅ Test 2 — AI Router Intent (HTTP 200 live hit)
```
$ ./mifos route --prompt "find a client by name"

✅  HTTP 200
{
  "status": "success",
  "tools_loaded": ["clients"]
}

$ ./mifos route --prompt "deposit money into savings account"

✅  HTTP 200
{
  "status": "success",
  "tools_loaded": ["savings"]
}
```
Router correctly resolves domain intent in both cases.

<!-- slide -->
### ✅ Test 3 — Clients Search (HTTP 200 live hit)
```
$ ./mifos clients search --name "John"

✅  HTTP 200
[]
```
HTTP 200 with an empty array — Fineract connected, no client named "John" yet.

<!-- slide -->
### ✅ Test 4 — Fineract Error Propagation (HTTP 400, expected)
```
$ ./mifos loans get 1
⚠️  HTTP 400:
{ "detail": "Fineract Error: The requested resource is not available." }

$ ./mifos savings get 1
⚠️  HTTP 400:
{ "detail": "Fineract Error: The requested resource is not available." }
```
**This is correct behaviour.** Loan/Savings ID 1 doesn't exist in a fresh Fineract DB. The CLI correctly relays the Fineract error — it is NOT a CLI bug.

<!-- slide -->
### ✅ Test 5 — Loans Sub-command Help
```
$ ./mifos loans --help

Available Commands:
  approve     Approve and disburse a loan
  create      Apply for a new loan
  get         Get full details for a loan
  late-fee    Apply a late fee to a loan
  reject      Reject a loan application
  repay       Make a repayment on a loan
  schedule    Get the repayment schedule for a loan
  waive       Waive interest on a loan
```
All 8 loan sub-commands present.
````

---

## Summary

| Test | Command | Result |
|------|---------|--------|
| Help | `./mifos --help` | ✅ All 5 groups shown |
| Route: clients | `./mifos route --prompt "find a client"` | ✅ HTTP 200 |
| Route: savings | `./mifos route --prompt "deposit into savings"` | ✅ HTTP 200 |
| Search clients | `./mifos clients search --name "John"` | ✅ HTTP 200 (empty list) |
| Loans/Savings get | `./mifos loans get 1` | ✅ HTTP 400 (resource not found — expected) |
| Loans help | `./mifos loans --help` | ✅ All 8 sub-commands listed |
