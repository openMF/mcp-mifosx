# Contributing to mcp-mifosx

Thank you for contributing! This guide walks you through adding MCP tools and maintains quality standards.

---

## How MCP Tools Work

The Model Context Protocol (MCP) enables AI agents to safely call banking operations through standardized tool definitions.

### Architecture Overview

```
User Query
    ↓
LLM Agent
    ↓
MCP Tool Invocation (JSON)
    ↓
Tool Validation Layer (schema checks, input validation)
    ↓
Domain Function (business logic, error handling)
    ↓
Fineract REST API Adapter
    ↓
Apache Fineract / Mifos X Backend
    ↓
Structured Response (consistent JSON)
    ↓
LLM Processing → User Response
```

### Tool Lifecycle

1. **Tool Definition** (`@tool` decorator in `python/mcp_server.py`)
   - Name, description, type hints for parameters
   - Returned to LLM as a function signature

2. **Parameter Validation** (`validate_tool_params()` wrapper)
   - Centralized pre-validation of all inputs
   - Consistent error structure returned early if invalid

3. **Domain Logic** (functions in `python/tools/domains/`)
   - Business rules (e.g., must be approved before disbursing)
   - Fineract API integration and field mapping

4. **API Gateway** (`python/tools/mcp_adapter.py`)
   - HTTP REST calls to Apache Fineract
   - Error normalization and response parsing

5. **Logging & Monitoring**
   - Tool name, parameters, execution time, status
   - Safe serialization for audit trails

---

## Example Flow: Create Loan

### User Request
```
"Create a loan for client #456 with principal 5000, 12-month term"
```

### MCP Tool Invocation
```json
{
  "tool": "create_loan",
  "params": {
    "client_id": 456,
    "principal": 5000,
    "term_months": 12
  }
}
```

### Validation (Pre-Check)
```python
# Centralized validation catches:
# - client_id not a positive integer
# - principal not positive
# - term_months not a positive integer
# Returns early with structured error if any fail
```

### Domain Function
```python
def create_loan(client_id, principal, term_months):
    """Domain logic: verify client exists, select product, call Fineract"""
    # Business logic, error handling, field mapping
```

### Fineract API Call
```
POST /loans
{
  "clientId": 456,
  "principal": 5000,
  "expectedDisbursementDate": "...",
  ...
}
```

### Response (Success)
```json
{
  "resourceId": 789,
  "changes": {"external_id": "LOAN-456-1"},
  "httpStatusCode": 200
}
```

### Response (Validation Failure)
```json
{
  "error": "Invalid client_id. It must be a positive integer.",
  "httpStatusCode": 400,
  "validation": {
    "field": "client_id",
    "value": "abc",
    "expected": "positive integer"
  }
}
```

---

## How to Add a New MCP Tool

### Step 1: Define Domain Function

Create or update a domain file in `python/tools/domains/` (e.g., `loans.py`):

```python
from tools.mcp_adapter import fineract_client

@tool
def my_new_tool(param_id: int, amount: float):
    """One-line description answering: 'What does this do?'
    
    Longer description of behavior, side effects, and common use cases.
    
    Args:
        param_id: Positive integer identifier (required)
        amount: Positive monetary amount in currency units (required)
        
    Returns:
        dict: Consistent JSON response with:
            - resourceId: created/updated resource ID if applicable
            - httpStatusCode: 200 on success, 4xx on validation/client error, 5xx on server error
            - error: human-readable error message if failed
            - Additional domain-specific fields as needed
            
    Example:
        >>> my_new_tool(123, 500.0)
        {'resourceId': 456, 'httpStatusCode': 200}
    """
    # Optional: Pre-validation can happen in domain function
    if param_id < 1:
        return {
            "error": "param_id must be positive",
            "httpStatusCode": 400,
        }
    
    # Call Fineract API via adapter
    response = fineract_client.execute_post(
        "endpoint/path",
        {
            "paramId": param_id,
            "amount": amount,
        }
    )
    
    return response
```

### Step 2: Register in MCP Server

In `python/mcp_server.py`, import your domain function and it will be **automatically wrapped** with centralized validation:

```python
# At the top with other domain imports:
from tools.domains.loans import my_new_tool

# The @mcp.tool() decorator is applied automatically in _enable_mcp_tool_validation()
# Your function signature becomes the MCP tool schema
```

### Step 3: Add to Validation Rules (if needed)

Edit `python/tools/validation.py` to extend `validate_tool_params()` if your parameters follow non-standard patterns:

```python
def validate_tool_params(params: dict[str, Any]) -> dict | None:
    # Existing rules handle:
    # - *Id / *_id fields (positive integers)
    # - amount/principal/rate fields (positive numbers)
    # - date fields (non-empty strings)
    
    # Add custom rules for your tool parameters here if needed:
    if "customField" in params:
        if not isinstance(params["customField"], str):
            return validation_error_response(
                message="customField must be a string",
                field="customField",
                value=params["customField"],
                expected="string"
            )
    
    return None
```

### Step 4: Add Tests

Create test file `python/tests/test_my_new_tool.py`:

```python
import pytest
from tools.domains.loans import my_new_tool
from tools.mcp_adapter import fineract_client
from unittest.mock import patch, MagicMock

def test_my_new_tool_success():
    """Test successful case"""
    with patch.object(fineract_client, 'execute_post') as mock_post:
        mock_post.return_value = {'resourceId': 456, 'httpStatusCode': 200}
        result = my_new_tool(123, 500.0)
        assert result['resourceId'] == 456
        mock_post.assert_called_once()

def test_my_new_tool_invalid_param_id():
    """Test validation: invalid param_id"""
    result = my_new_tool(-1, 500.0)
    assert result['httpStatusCode'] == 400
    assert 'error' in result

def test_my_new_tool_api_failure():
    """Test API error handling"""
    with patch.object(fineract_client, 'execute_post') as mock_post:
        mock_post.return_value = {'error': 'Resource not found', 'httpStatusCode': 404}
        result = my_new_tool(123, 500.0)
        assert result['httpStatusCode'] == 404
```

### Step 5: Run Tests

```bash
cd python
python -m pytest tests/test_my_new_tool.py -v
```

### Step 6: Document & PR

- Update `README.md` if adding a new domain or category
- Follow commit message format: `feat(domain): brief description`
- Link Jira ticket: `Closes AI-123`

### Demo Requirement for New Tools

- Contributors must include proof that their MCP tool works correctly.
- Accepted formats:
    - Screenshot of terminal output showing tool execution and structured response
    - Short demo video
- The screenshot should clearly show:
    - Tool name
    - Execution status (success/failure)
    - Structured JSON response
- Example reference:
    - A sample screenshot demonstrating `get_loan_details` MCP tool execution is included in the PR.

Expected output format:

```text
TOOL: get_loan_details
STATUS: success
OUTPUT:
{
    "id": 1,
    "status": {
        "value": "Active"
    },
    "summary": {
        "totalOutstanding": 1500
    }
}
```

---

## Code Quality Standards

### Validation

- **Input validation is centralized** in `validate_tool_params()`
- **No hard-coded checks** in individual domain functions
- **Consistent error structure**:
  ```json
  {
    "error": "human-readable message",
    "httpStatusCode": 400,
    "validation": {
      "field": "parameter_name",
      "value": "invalid_value",
      "expected": "expected_type_or_format"
    }
  }
  ```

### Logging

- All MCP tool calls are logged centrally
- Log includes: tool name, parameters, execution time, status
- Example log output:
  ```
  2025-03-30 10:15:42 - INFO - [MCP Server] Tool: create_loan | Params: {'client_id': 456, ...} | Status: success | Duration: 0.23s
  2025-03-30 10:15:45 - WARNING - [MCP Server] Tool: create_loan | Params: {...} | Status: validation_error | Error: Invalid client_id
  ```

### Error Handling

- Validation errors: return early with 400 status
- Client errors (not found, already exists): return 4xx with error message
- Server errors (API failure): return 5xx and log exception
- **Never expose internal API errors directly**; normalize for LLM understanding

### Testing

- **Success case**: happy path with valid inputs
- **Invalid input**: each parameter type validates correctly
- **API failure**: graceful error handling for Fineract errors
- **Edge cases**: empty results, boundary conditions

---

## Development Workflow

### 1. Sync with upstream
```bash
git fetch origin
git rebase origin/main
```

### 2. Create feature branch
```bash
git checkout -b feature/AI-206-add-new-tool
```

### 3. Implement & test
```bash
# Make code changes
python -m pytest python/tests/ -v
python -m pylint python/tools/ --disable=C0111  # basic linting
```

### 4. Commit with ticket reference
```bash
git add .
git commit -m "feat(loans): add my_new_tool for X functionality

- Added domain function with validation
- Added comprehensive tests (success, invalid input, API error)
- Integrated with centralized validation wrapper

Closes AI-206"
```

### 5. Push and create PR
```bash
git push origin feature/AI-206-add-new-tool
# Create PR via GitHub UI or gh CLI
```

### 6. Review feedback
- Respond to PR comments
- Update code as needed
- Re-run tests before pushing updates

---

## Backward Compatibility

When updating existing tools:
1. **Do not remove parameters** from signatures (breaks existing callers)
2. **Make new parameters optional** with sensible defaults
3. **Test against existing tool contracts** to ensure no breaking changes
4. **Log deprecation warnings** for planned removals (3 months notice)

---

## Performance Considerations

- **Validation is fast** (O(n) parameter count, not API calls)
- **Fineract calls are the bottleneck** (network I/O), not tool wrapper overhead
- **Logging is async** where possible to avoid blocking tool execution
- For bulk operations, use domain-specific batch endpoints

---

## Questions?

- **Tool behavior**: Check `python/tools/domains/` for similar examples
- **Validation rules**: See `python/tools/validation.py`
- **Fineract API**: Refer to [Apache Fineract REST API docs](https://fineract.apache.org/en/docs/current/deployment/deploy_docker.html)
- **PR reviews**: Tag maintainers in GitHub for guidance

---

**Thank you for making mcp-mifosx better!**
