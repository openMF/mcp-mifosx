def get_client_details(client_id: int):
    """
    Fetch client details by client_id.
    """

    # Validation
    if not isinstance(client_id, int) or client_id <= 0:
        return {"error": "Invalid client_id"}

    # Placeholder response (can be replaced with real API call)
    return {
        "client_id": client_id,
        "name": "Sample Client",
        "status": "active"
    }