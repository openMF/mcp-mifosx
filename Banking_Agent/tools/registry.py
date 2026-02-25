"""
Tool Router Registry
Routes AI agent intent prompts to the relevant set of tools/domains.
"""

# Keyword → domain mappings
INTENT_MAP = {
    "clients": ["client", "customer", "person", "user", "borrower"],
    "loans": ["loan", "borrow", "credit", "repay", "disburse", "approve"],
    "savings": ["saving", "deposit", "withdraw", "account", "balance"],
}


class ToolRouter:
    def route_intent(self, prompt: str) -> list[str]:
        """
        Given a natural-language prompt, returns the list of tool domains
        that are relevant.

        Args:
            prompt: The AI agent's natural language intent string.

        Returns:
            A list of domain names that should be loaded, e.g. ["clients", "loans"]
        """
        prompt_lower = prompt.lower()
        active_tools = []

        for domain, keywords in INTENT_MAP.items():
            if any(kw in prompt_lower for kw in keywords):
                active_tools.append(domain)

        # If nothing matched, return all tools as a safe fallback
        if not active_tools:
            active_tools = list(INTENT_MAP.keys())

        return active_tools


# Singleton instance used by api_server
router = ToolRouter()