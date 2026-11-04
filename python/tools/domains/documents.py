# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Document capture + KYC-file tools.

`validate_capture_quality` runs the deterministic document capture-quality
gate (see tools/capture_gate.py) on
a photo a field officer just took, BEFORE it becomes a permanent client
document: an unreadable capture returns RETAKE and never reaches Fineract,
borderline ones are flagged for human REVIEW, usable ones proceed.

`upload_client_document` composes the same pre-flight gate with the
Fineract client-document upload, so an agent can refuse a useless capture
instead of storing it server-side.

`verify_client_details` covers the content side: the vision model in the
AI client reads the photo and extracts fields; this tool fetches the
client's record from Fineract and compares them deterministically
(tools/verify_fields.py) — per-field MATCH / REVIEW / MISMATCH verdicts,
never an LLM guess.
"""

import os

from langchain_core.tools import tool

from tools.capture_gate import VERDICT_RETAKE, assess_image
from tools.mcp_adapter import fineract_client
from tools.verify_fields import verify_fields

# A hard-RETAKE capture is never uploaded on its own; REVIEW is the
# officer's call, so overruling the gate (any verdict) requires force=True.
_GATE_BLOCKED = VERDICT_RETAKE
_MAX_CAPTURE_BYTES = 40 * 1024 * 1024


@tool
def validate_capture_quality(image_path: str):
    """Answers: 'Is this document photo readable?' or 'Gate this capture before upload'"""
    return _gate_response(image_path)


def _gate_response(image_path: str) -> dict:
    """Gate one image file by path, with readable errors. No server round-trip."""
    try:
        size = os.path.getsize(image_path)
        if size == 0:
            return {"error": f"{image_path} is empty — capture again"}
        if size > _MAX_CAPTURE_BYTES:
            return {"error": (f"{image_path} is {size} bytes; captures over "
                              f"{_MAX_CAPTURE_BYTES} are rejected before reading")}
        with open(image_path, "rb") as fh:
            return assess_image(fh.read(), filename=image_path)
    except IsADirectoryError:
        return {"error": f"{image_path} is a directory — pass the photo file"}
    except FileNotFoundError:
        return {"error": f"image not found: {image_path}"}
    except ValueError as e:
        # gate-reported decode failure: which file, why
        return {"error": str(e)}
    except ImportError as e:
        return {"error": str(e)}
    except OSError as e:
        return {"error": f"cannot read {image_path}: {str(e)}"}


@tool
def upload_client_document(
    clientId: int,
    imagePath: str,
    name: str = None,
    description: str = None,
    force: bool = False,
):
    """Answers: "Gate + upload this photo to the client's documents". Refuses an unreadable capture."""
    gate = _gate_response(imagePath)
    if "error" in gate:
        return gate
    verdict = gate.get("verdict")
    if verdict is None:
        # fail closed: a gate response lacking a verdict never uploads
        return {"error": f"capture gate could not assess {imagePath}"}
    if verdict == _GATE_BLOCKED and not force:
        # the gate refused: the response carries the verdict and the gates
        # that tripped, so the officer retakes instead of the agent
        # retrying the same bad upload forever
        return {"upload_rejected": True, **gate}

    file_name = os.path.basename(imagePath)
    form = {k: v for k, v in {"name": name, "description": description}.items() if v}
    if not form:
        form = {"name": file_name}
    result = fineract_client.execute_multipart(f"clients/{clientId}/documents", form, imagePath)
    if "error" in result:
        return result
    # the capture made it to Fineract: carry the gate verdict + signals in
    # the audit trail so any upload past the gate stays traceable
    result["capture_gate"] = {k: gate[k] for k in ("verdict", "reasons", "signals") if k in gate}
    return result


@tool
def verify_client_details(
    clientId: int,
    fullName: str = None,
    nationalId: str = None,
    mobileNo: str = None,
):
    """Answers: 'Verify this ID document belongs to the client' / 'Check the OCR-extracted fields'"""
    extracted = {"full_name": fullName, "national_id": nationalId, "mobile_no": mobileNo}
    if not any(str(v or "").strip() for v in extracted.values()):
        return {
            "error": ("fields must carry at least one non-empty extracted value "
                      "(fullName, nationalId, mobileNo)"),
        }
    profile = fineract_client.execute_get(f"clients/{clientId}")
    if "error" in profile:
        return profile
    identifiers = fineract_client.execute_get(f"clients/{clientId}/identifiers")
    if "error" in identifiers:
        identifiers = []
    if isinstance(identifiers, dict):
        identifiers = identifiers.get("items", [])
    profile["identifiers"] = identifiers
    return verify_fields(extracted, profile)
