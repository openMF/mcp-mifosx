# Fineract Agent Skill — Tool Catalog

Complete list of all 66 tools organized by domain. Each entry shows the tool name, description, required parameters, and optional parameters.

---

## Domain: `clients` (15 tools)

| Tool | Description | Required Params | Optional Params |
|---|---|---|---|
| `search_clients` | Search for clients by name | `name_query: str` | — |
| `get_client_details` | Get detailed info for a client | `client_id: int` | — |
| `get_client_accounts` | Show all loans and savings for a client | `client_id: int` | — |
| `create_client` | Create a new banking client | `firstname: str`, `lastname: str` | `mobile_no: str`, `office_id: int=1`, `active: bool=True` |
| `activate_client` | Activate a pending client | `client_id: int` | — |
| `update_client` | Update client details | `client_id: int` | `firstname`, `lastname`, `mobile_no`, `external_id` |
| `close_client` | Close a client profile | `client_id: int` | `closure_reason_id: int=17` |
| `delete_client` | Delete a pending/closed client | `client_id: int` | — |
| `get_client_identifiers` | List identity documents | `client_id: int` | — |
| `create_client_identifier` | Add an identity document | `client_id: int`, `document_type_id: int`, `document_key: str` | — |
| `get_client_documents` | List uploaded files | `client_id: int` | — |
| `get_client_charges` | List client-level fees | `client_id: int` | — |
| `apply_client_charge` | Apply a one-time charge | `client_id: int`, `charge_id: int`, `amount: float` | — |
| `get_client_transactions` | List financial transactions | `client_id: int` | — |
| `get_client_addresses` | Get registered addresses | `client_id: int` | — |

## Domain: `loans` (15 tools)

| Tool | Description | Required Params | Optional Params |
|---|---|---|---|
| `get_loan_details` | Get loan status, principal, balance, timeline | `loan_id: int` | — |
| `get_repayment_schedule` | Get repayment schedule | `loan_id: int` | — |
| `get_loan_transactions` | Get transaction history | `loan_id: int` | — |
| `get_loan_template` | Get pre-filled loan template | `client_id: int` | `product_id: int` |
| `create_loan` | Create individual loan application | `client_id: int`, `principal: float`, `months: int` | `product_id: int=1` |
| `create_group_loan` | Create group loan application | `group_id: int`, `principal: float`, `months: int` | `product_id: int=1` |
| `approve_and_disburse_loan` | Approve and disburse in one action | `loan_id: int` | `amount: float` |
| `reject_loan` | Reject a pending loan | `loan_id: int` | `note: str` |
| `make_repayment` | Make a loan repayment | `loan_id: int`, `amount: float` | — |
| `waive_interest` | Waive interest on a loan | `loan_id: int`, `amount: float` | `note: str` |
| `undo_loan_approval` | Revert to pending status | `loan_id: int` | — |
| `undo_loan_disbursal` | Revert to approved status | `loan_id: int` | — |
| `update_loan` | Update a draft loan | `loan_id: int` | `principal`, `months`, `product_id` |
| `delete_loan` | Delete a draft loan | `loan_id: int` | — |
| `reschedule_loan` | Submit reschedule request | `loan_id: int`, `reschedule_from_date: str`, `reschedule_reason_id: int` | `adjusted_due_date`, `new_interest_rate`, `grace_on_principal`, `extra_terms`, `reason` |

## Domain: `savings` (9 tools)

| Tool | Description | Required Params | Optional Params |
|---|---|---|---|
| `get_savings_account` | Get savings account details | `account_id: int` | — |
| `get_savings_transactions` | Get transaction history | `account_id: int` | — |
| `create_savings_account` | Create new savings account | `client_id: int` | `product_id: int=1` |
| `approve_and_activate_savings` | Approve and activate in one action | `account_id: int` | — |
| `close_savings_account` | Close a savings account | `account_id: int` | — |
| `deposit` | Deposit into savings | `account_id: int`, `amount: float` | — |
| `withdraw` | Withdraw from savings | `account_id: int`, `amount: float` | — |
| `apply_savings_charge` | Apply a charge/fee | `account_id: int`, `amount: float` | `charge_id: int=1` |
| `calculate_and_post_interest` | Post accrued interest | `account_id: int` | — |

## Domain: `groups` (8 tools)

| Tool | Description | Required Params | Optional Params |
|---|---|---|---|
| `create_group` | Create a lending group | `name: str` | `office_id: int=1`, `external_id: str` |
| `get_group` | Show group details and members | `group_id: int` | — |
| `list_groups` | List all groups | — | `office_id: int` |
| `activate_group` | Activate a pending group | `group_id: int` | — |
| `add_group_member` | Add a client to a group | `group_id: int`, `client_id: int` | — |
| `list_centers` | List all centers | — | `office_id: int` |
| `get_center` | Get center details | `center_id: int` | — |
| `create_center` | Create a new center | `name: str`, `office_id: int` | `external_id: str` |

## Domain: `accounting` (3 tools)

| Tool | Description | Required Params | Optional Params |
|---|---|---|---|
| `list_gl_accounts` | List GL accounts (Chart of Accounts) | — | `account_type: int` (1=Asset…5=Expense) |
| `get_journal_entries` | List journal entries | — | `gl_account_id: int`, `transaction_id: str` |
| `create_journal_entry` | Record a manual journal entry | `office_id: int`, `date: str`, `credits: list`, `debits: list` | `comment: str` |

## Domain: `staff` (4 tools)

| Tool | Description | Required Params | Optional Params |
|---|---|---|---|
| `list_staff` | List staff members | — | `office_id: int`, `status: str` (all/active/inactive) |
| `get_staff_details` | Get staff member details | `staff_id: int` | — |
| `list_offices` | List all offices/branches | — | — |
| `get_office_details` | Get office details | `office_id: int` | — |

## Domain: `products` (4 tools)

| Tool | Description | Required Params | Optional Params |
|---|---|---|---|
| `list_loan_products` | List all loan products | — | — |
| `get_loan_product` | Get loan product details | `product_id: int` | — |
| `list_savings_products` | List all savings products | — | — |
| `get_savings_product` | Get savings product details | `product_id: int` | — |

## Domain: `charges` (4 tools)

| Tool | Description | Required Params | Optional Params |
|---|---|---|---|
| `list_charges` | List charge definitions | — | — |
| `get_charge` | Get charge details | `charge_id: int` | — |
| `create_charge` | Create a charge definition | `name: str`, `amount: float`, `charge_time_type: int` | `currency_code`, `charge_applies_to`, `charge_calculation_type`, `is_penalty`, `is_active` |
| `update_charge` | Update a charge definition | `charge_id: int` | `name`, `amount`, `is_active` |

## Domain: `reports` (5 tools)

| Tool | Description | Required Params | Optional Params |
|---|---|---|---|
| `list_reports` | List report definitions | — | `report_type: str` |
| `get_report` | Get report definition | `report_id: int` | — |
| `run_report` | Run a report by name | `report_name: str` | `params: dict` |
| `create_report` | Register a report definition | `report_name: str`, `report_type: str`, `report_sql: str` | `description: str` |
| `update_report` | Update a report definition | `report_id: int` | `report_name`, `report_type`, `report_sql`, `description` |

---

## Fineract API Endpoints Covered

| Endpoint | Methods | Skill Domain |
|---|---|---|
| `/search` | GET | `clients` |
| `/clients` | GET, POST, PUT, DELETE | `clients` |
| `/clients/{id}/accounts` | GET | `clients` |
| `/clients/{id}/identifiers` | GET, POST | `clients` |
| `/clients/{id}/documents` | GET | `clients` |
| `/clients/{id}/charges` | GET, POST | `clients` |
| `/clients/{id}/transactions` | GET | `clients` |
| `/clients/{id}/addresses` | GET | `clients` |
| `/loans` | GET, POST, PUT, DELETE | `loans` |
| `/loans/{id}/transactions` | POST | `loans` |
| `/loans/template` | GET | `loans` |
| `/rescheduleloans` | POST | `loans` |
| `/savingsaccounts` | GET, POST | `savings` |
| `/savingsaccounts/{id}/transactions` | POST | `savings` |
| `/savingsaccounts/{id}/charges` | POST | `savings` |
| `/groups` | GET, POST | `groups` |
| `/centers` | GET, POST | `groups` |
| `/glaccounts` | GET | `accounting` |
| `/journalentries` | GET, POST | `accounting` |
| `/staff` | GET | `staff` |
| `/offices` | GET | `staff` |
| `/loanproducts` | GET | `products` |
| `/savingsproducts` | GET | `products` |
| `/charges` | GET, POST, PUT | `charges` |
| `/reports` | GET, POST, PUT | `reports` |
| `/runreports/{name}` | GET | `reports` |
