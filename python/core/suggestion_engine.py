from typing import Any, List
from tools.domains.loan_suggestions import generate_loan_suggestions

def generate_suggestions(intent: str, data: Any) -> List[str]:
    suggestions: List[str] = []

    suggestions.extend(generate_loan_suggestions(intent, data))

    return suggestions