import os
import requests
import logging
from dotenv import load_dotenv

# 1. Set up enterprise-grade logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - [MCP Adapter] %(message)s')
logger = logging.getLogger(__name__)

# Load variables from the .env file
load_dotenv(dotenv_path=os.path.join(os.path.dirname(__file__), "..", ".env"))

class FineractAdapter:
    def __init__(self):
        self.base_url = os.getenv("MIFOSX_BASE_URL")
        self.tenant_id = os.getenv("MIFOSX_TENANT_ID", "default")
        self.username = os.getenv("MIFOSX_USERNAME")
        self.password = os.getenv("MIFOSX_PASSWORD")
        
        # 🛑 Suppress SSL warnings for local self-signed certs (Docker)
        requests.packages.urllib3.disable_warnings(
            requests.packages.urllib3.exceptions.InsecureRequestWarning
        )

    def _get_headers(self):
        """Builds the mandatory headers for Fineract."""
        return {
            "Fineract-Platform-TenantId": self.tenant_id,
            "Content-Type": "application/json"
        }

    def _parse_fineract_error(self, response: requests.Response):
        """Extracts readable error messages from Fineract's complex JSON error responses."""
        try:
            error_data = response.json()
            # Fineract usually puts the human-readable error here:
            if "developerMessage" in error_data:
                return f"Fineract Error: {error_data['developerMessage']}"
            elif "errors" in error_data and len(error_data["errors"]) > 0:
                return f"Validation Error: {error_data['errors'][0].get('defaultUserMessage', 'Unknown error')}"
            return f"Error {response.status_code}: {response.text}"
        except Exception:
            return f"HTTP {response.status_code}: Failed to parse error response."

    def execute_get(self, endpoint: str, params: dict = None):
        """Executes a standard GET request."""
        url = f"{self.base_url}/{endpoint}"
        logger.info(f"Executing GET: {url}")
        try:
            response = requests.get(
                url, headers=self._get_headers(), auth=(self.username, self.password), 
                params=params, verify=False
            )
            response.raise_for_status()
            return response.json()
        except requests.exceptions.RequestException as e:
            if e.response is not None:
                return {"error": self._parse_fineract_error(e.response)}
            return {"error": f"Connection failed: {str(e)}"}

    def execute_post(self, endpoint: str, payload: dict):
        """Executes a POST request (for creating records)."""
        url = f"{self.base_url}/{endpoint}"
        import json
        logger.info(f"Executing POST: {url}")
        logger.info(f"Payload: {json.dumps(payload)}")
        try:
            response = requests.post(
                url, headers=self._get_headers(), auth=(self.username, self.password), 
                json=payload, verify=False
            )
            response.raise_for_status()
            return response.json()
        except requests.exceptions.RequestException as e:
            if e.response is not None:
                return {"error": self._parse_fineract_error(e.response)}
            return {"error": f"Connection failed: {str(e)}"}

    def execute_put(self, endpoint: str, payload: dict):
        """Executes a PUT request (for updating records)."""
        url = f"{self.base_url}/{endpoint}"
        logger.info(f"Executing PUT: {url}")
        try:
            response = requests.put(
                url, headers=self._get_headers(), auth=(self.username, self.password), 
                json=payload, verify=False
            )
            response.raise_for_status()
            return response.json()
        except requests.exceptions.RequestException as e:
            if e.response is not None:
                return {"error": self._parse_fineract_error(e.response)}
            return {"error": f"Connection failed: {str(e)}"}

    def execute_delete(self, endpoint: str):
        """Executes a DELETE request (for closing/withdrawing records)."""
        url = f"{self.base_url}/{endpoint}"
        logger.info(f"Executing DELETE: {url}")
        try:
            response = requests.delete(
                url, headers=self._get_headers(), auth=(self.username, self.password),
                verify=False
            )
            response.raise_for_status()
            return response.json()
        except requests.exceptions.RequestException as e:
            if e.response is not None:
                return {"error": self._parse_fineract_error(e.response)}
            return {"error": f"Connection failed: {str(e)}"}


# Initialize the global, stateless adapter instance
fineract_client = FineractAdapter()


# ==========================================
# 🚀 DIAGNOSTIC TEST (Runs only if executed directly)
# ==========================================
if __name__ == "__main__":
    print("\n" + "="*50)
    print("🏦 INITIATING FINERACT ADAPTER DIAGNOSTICS")
    print("="*50)
    
    # Test 1: Ping the Users endpoint to verify auth and SSL bypass
    print("\n[Test 1] Fetching System Users...")
    result = fineract_client.execute_get("users")
    
    if "error" in result:
        logger.error(f"Diagnostics Failed: {result['error']}")
        print("\n❌ Make sure your Docker container is fully running and .env is correct.")
    else:
        logger.info("Connection Successful!")
        print(f"✅ Found {len(result)} users in the Fineract Database.")
        if len(result) > 0:
            print(f"👤 First User ID: {result[0].get('id')} | Username: {result[0].get('username')}")
    print("\n" + "="*50 + "\n")