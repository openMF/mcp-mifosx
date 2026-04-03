def test_invalid_client_id():
    result = get_client_details(-1)
    assert "error" in result