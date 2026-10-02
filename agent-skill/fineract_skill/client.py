# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""
Low-level HTTP client for the Apache Fineract REST API.

Handles authentication, tenant headers, error parsing, and SSL
configuration.  All domain tools delegate to this client.
"""

from __future__ import annotations

import logging
import os
from typing import Any

import httpx
from dotenv import load_dotenv

load_dotenv()

logger = logging.getLogger("fineract_skill.client")


class FineractError(Exception):
    """Raised when the Fineract API returns an error response."""

    def __init__(self, status_code: int, message: str, raw: Any = None):
        self.status_code = status_code
        self.message = message
        self.raw = raw
        super().__init__(f"HTTP {status_code}: {message}")


class FineractClient:
    """Stateless, reusable HTTP client for Fineract.

    Configuration is read from environment variables (see `.env.example`)
    or can be passed directly via constructor kwargs.

    Parameters
    ----------
    base_url : str, optional
        Fineract API base URL.  Defaults to ``FINERACT_BASE_URL`` env var.
    username : str, optional
        HTTP Basic-Auth username.  Defaults to ``FINERACT_USERNAME``.
    password : str, optional
        HTTP Basic-Auth password.  Defaults to ``FINERACT_PASSWORD``.
    tenant_id : str, optional
        Fineract tenant identifier.  Defaults to ``FINERACT_TENANT_ID`` or ``"default"``.
    timeout : float, optional
        Request timeout in seconds.  Defaults to ``FINERACT_TIMEOUT`` or ``30``.
    verify_ssl : bool, optional
        Whether to verify TLS certificates.  Defaults to the inverse of
        ``FINERACT_SKIP_TLS_VERIFY``.
    """

    def __init__(
        self,
        base_url: str | None = None,
        username: str | None = None,
        password: str | None = None,
        tenant_id: str | None = None,
        timeout: float | None = None,
        verify_ssl: bool | None = None,
    ):
        self.base_url = (base_url or os.getenv("FINERACT_BASE_URL", "")).rstrip("/")
        self.username = username or os.getenv("FINERACT_USERNAME", "mifos")
        self.password = password or os.getenv("FINERACT_PASSWORD", "password")
        self.tenant_id = tenant_id or os.getenv("FINERACT_TENANT_ID", "default")
        self.timeout = timeout or float(os.getenv("FINERACT_TIMEOUT", "30"))

        if verify_ssl is None:
            self.verify_ssl = os.getenv("FINERACT_SKIP_TLS_VERIFY", "false").lower() != "true"
        else:
            self.verify_ssl = verify_ssl

        if not self.base_url:
            raise ValueError(
                "FINERACT_BASE_URL is not set. "
                "Pass base_url= or set the FINERACT_BASE_URL environment variable."
            )

        if self.base_url.startswith("http://") and self.username and self.password:
            raise ValueError(
                "Unencrypted HTTP URL configured with Basic Auth credentials. "
                "Use HTTPS for the Fineract API."
            )

    # ── Internal helpers ───────────────────────────────────────────────

    def _headers(self) -> dict[str, str]:
        return {
            "Fineract-Platform-TenantId": self.tenant_id,
            "Content-Type": "application/json",
            "Accept": "application/json",
        }

    def _auth(self) -> httpx.BasicAuth:
        return httpx.BasicAuth(self.username, self.password)

    @staticmethod
    def _parse_error(response: httpx.Response) -> str:
        """Extract a human-readable error message from a Fineract error response."""
        try:
            body = response.json()
            if "developerMessage" in body:
                return body["developerMessage"]
            if "errors" in body and body["errors"]:
                return body["errors"][0].get("defaultUserMessage", "Unknown validation error")
            if "defaultUserMessage" in body:
                return body["defaultUserMessage"]
            return response.text[:500]
        except Exception:
            return f"HTTP {response.status_code}: {response.text[:300]}"

    def _request(
        self,
        method: str,
        endpoint: str,
        *,
        params: dict[str, Any] | None = None,
        json_body: dict[str, Any] | None = None,
    ) -> Any:
        """Execute an HTTP request and return the parsed JSON response.

        Raises
        ------
        FineractError
            If the server returns a non-2xx status code.
        """
        url = f"{self.base_url}/{endpoint.lstrip('/')}"
        logger.info("Fineract %s %s", method, url)

        with httpx.Client(verify=self.verify_ssl, timeout=self.timeout) as http:
            try:
                response = http.request(
                    method,
                    url,
                    headers=self._headers(),
                    auth=self._auth(),
                    params=params,
                    json=json_body,
                )
            except httpx.RequestError as exc:
                raise FineractError(0, f"Network error: {exc}") from exc

        if response.status_code >= 400:
            msg = self._parse_error(response)
            raise FineractError(response.status_code, msg, response)

        # Some Fineract endpoints return 204 No Content
        if response.status_code == 204 or not response.text.strip():
            return {"status": "success"}

        try:
            return response.json()
        except ValueError:
            return response.text

    # ── Public HTTP verbs ──────────────────────────────────────────────

    def get(self, endpoint: str, params: dict[str, Any] | None = None) -> Any:
        """Execute a GET request."""
        return self._request("GET", endpoint, params=params)

    def post(self, endpoint: str, payload: dict[str, Any] | None = None) -> Any:
        """Execute a POST request."""
        return self._request("POST", endpoint, json_body=payload or {})

    def put(self, endpoint: str, payload: dict[str, Any] | None = None) -> Any:
        """Execute a PUT request."""
        return self._request("PUT", endpoint, json_body=payload or {})

    def delete(self, endpoint: str) -> Any:
        """Execute a DELETE request."""
        return self._request("DELETE", endpoint)

    # ── Convenience: health check ──────────────────────────────────────

    def ping(self) -> dict[str, Any]:
        """Verify connectivity by hitting the ``/users`` endpoint.

        Returns a dict with ``status`` and ``user_count`` on success.
        """
        try:
            result = self.get("users")
            count = len(result) if isinstance(result, list) else 0
            return {"status": "ok", "user_count": count}
        except FineractError as exc:
            return {"status": "error", "message": str(exc)}
