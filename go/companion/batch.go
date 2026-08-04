// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-BATCH: the OFFLINE-SYNC drain endpoint. Every CommonPurse (mifos-x-group-banking) write can
// be queued to the app's local sync_queue when the device is offline; when connectivity returns,
// SyncManagerImpl replays the queue as ONE batch:
//
//	POST /fineract-provider/api/v1/batches
//	  { "requests": [ { requestId, relativeUrl, method, body }, … ] }
//	  -> [ { requestId, statusCode, body }, … ]
//
// Without this endpoint the drain 404s and NOTHING offline-queued ever syncs.
//
// SELF-DISPATCH: each queued operation is replayed through the companion's OWN mux, not proxied
// raw to Fineract — the queued paths target the companion's app-facing routes (e.g.
// /datatables/dt_loan_request, /loans/{id}/transactions?command=disburse), whose handlers do the
// clientId->path + column-name mapping / approve-then-disburse orchestration a raw Fineract call
// can't. The caller's bearer token is propagated to every sub-request so per-user auth still holds.
package companion

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
)

type batchOperationIn struct {
	RequestID   int    `json:"requestId"`
	RelativeURL string `json:"relativeUrl"`
	Method      string `json:"method"`
	Body        string `json:"body"`
}

type batchRequestIn struct {
	Requests []batchOperationIn `json:"requests"`
}

type batchResponseItem struct {
	RequestID  int    `json:"requestId"`
	StatusCode int    `json:"statusCode"`
	Body       string `json:"body"`
}

func (h *Handler) registerBatchRoutes(mux *http.ServeMux) {
	h.selfMux = mux
	mux.HandleFunc("POST /fineract-provider/api/v1/batches", h.HandleBatchSync)
}

// HandleBatchSync replays each queued write through the companion's own router and returns a
// per-operation status list, exactly matching the app's BatchSyncResponseItemDto contract.
func (h *Handler) HandleBatchSync(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	var req batchRequestIn
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid batch body")
		return
	}
	if h.selfMux == nil {
		writeErr(w, http.StatusInternalServerError, "internal", "router unavailable")
		return
	}
	auth := r.Header.Get("Authorization")
	out := make([]batchResponseItem, 0, len(req.Requests))
	for _, op := range req.Requests {
		path := op.RelativeURL
		// Strip the app's Fineract prefix when present so the path matches the companion's routes
		// (which the app calls WITHOUT the prefix for datatable/loan writes).
		path = strings.TrimPrefix(path, "/fineract-provider/api/v1")
		if !strings.HasPrefix(path, "/") {
			path = "/" + path
		}
		method := strings.ToUpper(strings.TrimSpace(op.Method))
		if method == "" {
			method = "POST"
		}
		sub := httptest.NewRequest(method, path, bytes.NewReader([]byte(op.Body)))
		sub.Header.Set("Content-Type", "application/json")
		if auth != "" {
			sub.Header.Set("Authorization", auth)
		}
		rec := httptest.NewRecorder()
		h.selfMux.ServeHTTP(rec, sub)
		body, _ := io.ReadAll(rec.Result().Body)
		out = append(out, batchResponseItem{
			RequestID:  op.RequestID,
			StatusCode: rec.Code,
			Body:       string(body),
		})
	}
	_ = json.NewEncoder(w).Encode(out)
}
