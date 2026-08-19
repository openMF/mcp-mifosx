// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package companion

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// TestAppRouteCoverage asserts that EVERY live request path the MifosSave (mifos-x-group-banking)
// app's 27 core/network services issue resolves to a registered companion route. Because the app is
// single-host (its base URL points at the companion) and the companion serves ONLY explicitly-
// registered routes (http_server.go — no catch-all Fineract proxy), an unregistered path is a hard
// 404 that breaks that screen on-device. This table is the API-availability contract; a new app call
// or a removed companion route trips it. Derived by composing each service's httpClient.<verb>(path)
// call sites (see server-layer/API_CONSISTENCY_FIX_PLAN.md §6 Phase 6.1).
func TestAppRouteCoverage(t *testing.T) {
	mux := http.NewServeMux()
	(&Handler{}).RegisterRoutes(mux)

	// {method, concrete path} — path vars filled with representative ids.
	cases := []struct{ method, path string }{
		// auth + dashboards
		{"POST", "/companion/auth/login"},
		{"POST", "/companion/auth/self-register"},
		{"GET", "/companion/auth/me"},
		{"GET", "/companion/organizer/dashboard"},
		{"GET", "/companion/member/dashboard"},
		// groups (companion BFF + bare-Fineract member read)
		{"POST", "/companion/groups"},
		{"GET", "/companion/groups/mine"},
		{"GET", "/companion/groups/24"},
		{"GET", "/companion/groups/24/my-role"},
		{"GET", "/companion/groups/24/corpus"},
		{"GET", "/companion/groups/24/accounts"},
		{"GET", "/companion/groups/24/loan-requests"},
		{"POST", "/companion/groups/24/associate-clients"},
		{"GET", "/groups/24"},        // loanapply/meetingconduct.getGroupMembers (G-A)
		{"GET", "/groups/24/clients"}, // memberlist
		{"GET", "/groups/24/loans"},   // loanlist
		// invitations (organizer + join-with-code)
		{"POST", "/companion/datatables/invitations/24"},
		{"GET", "/companion/datatables/invitations/24"},
		{"GET", "/companion/datatables/invitations/ABC123"}, // validate-by-code
		{"DELETE", "/companion/datatables/invitations/24/5"},
		{"PUT", "/companion/datatables/invitations/ABC123/5"},
		// group-type + offices
		{"GET", "/companion/datatables/group_type_config/0"},
		{"GET", "/offices"},
		// clients CRUD (G-C/D/E)
		{"POST", "/clients"},
		{"GET", "/clients/5"},
		{"GET", "/clients/5/accounts"},
		{"GET", "/clients/5/loans"},
		{"POST", "/clients/5/images"},
		// member role (G-4b + G-F)
		{"GET", "/datatables/dt_member_role/5"},
		{"POST", "/datatables/dt_member_role/5"},
		{"PUT", "/datatables/dt_member_role/5"},
		// field-officer (gaps 3/4a)
		{"GET", "/companion/field-officer/groups"},
		{"GET", "/companion/field-officer/report"},
		// self-service (gaps 1/2)
		{"PUT", "/self/user/updatePassword"},
		{"GET", "/self/savingsaccounts/9/transactions"},
		// savings summaries
		{"GET", "/companion/groups/24/savings"},
		{"GET", "/companion/groups/24/savings/individual"},
		{"GET", "/companion/groups/24/members/5/savings"},
		// loans
		{"GET", "/loanproducts"},
		{"GET", "/loans/template"},
		{"GET", "/loans/7"},
		{"GET", "/loans"},
		{"POST", "/loans"},
		{"POST", "/loans/7/transactions"},
		{"POST", "/datatables/dt_loan_request"},
		{"GET", "/datatables/dt_loan_vote/7"},
		{"POST", "/companion/loan-applications/5/disburse"},
		// group datatables
		{"GET", "/datatables/dt_group_corpus/24"},
		{"PUT", "/datatables/dt_group_corpus/24"},
		{"GET", "/datatables/dt_group_config/24"},
		// meeting lifecycle (prefixed + bare)
		{"GET", "/fineract-provider/api/v1/datatables/dt_meeting_schedule/24"}, // G-B
		{"GET", "/fineract-provider/api/v1/datatables/dt_meeting_record/24"},
		{"GET", "/fineract-provider/api/v1/datatables/dt_meeting_attendance/24-1"},
		{"GET", "/datatables/dt_meeting_record/24"},
		{"POST", "/datatables/dt_meeting_record"},
		{"POST", "/datatables/dt_meeting_attendance"},
		{"POST", "/savingsaccounts/9/transactions"},
		// shareout
		{"GET", "/companion/groups/24/shareout/preview"},
		{"POST", "/companion/groups/24/shareout/execute"},
		{"POST", "/companion/groups/24/rotation/execute"},
		// batch offline replay
		{"POST", "/fineract-provider/api/v1/batches"},
	}

	for _, c := range cases {
		req := httptest.NewRequest(c.method, c.path, nil)
		_, pattern := mux.Handler(req)
		if pattern == "" {
			t.Errorf("NO companion route for app call: %s %s (would 404 on-device)", c.method, c.path)
		}
	}
}
