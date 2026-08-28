// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package companion

// Organizer-dashboard companion endpoint. The MifosSave app's
// OrganizerDashboardApiImpl calls GET /companion/organizer/dashboard and expects
// OrganizerDashboardSummaryDto (core/network/.../model/OrganizerDashboardDto.kt).
// This aggregates the organizer's active groups on Fineract (same reads as
// /companion/groups/mine) into the KPI summary the dashboard renders.

import (
	"encoding/json"
	"net/http"
	"strconv"
	"sync"
)

// OrganizerDashboardSummaryDto mirrors the app DTO (field names/casing exact).
type OrganizerDashboardSummaryDto struct {
	OrganizerName        string                       `json:"organizerName"`
	MyGroupCount         int                          `json:"myGroupCount"`
	TotalMembers         int                          `json:"totalMembers"`
	PendingShareOutCount int                          `json:"pendingShareOutCount"`
	MeetingsTodayCount   int                          `json:"meetingsTodayCount"`
	FieldOfficerEnabled  bool                         `json:"fieldOfficerEnabled"`
	TodaySchedule        []OrganizerScheduledMeeting  `json:"todaySchedule"`
	RecentActivity       []OrganizerActivityItem      `json:"recentActivity"`
}

type OrganizerScheduledMeeting struct {
	GroupID     string `json:"groupId"`
	GroupName   string `json:"groupName"`
	MeetingTime string `json:"meetingTime"`
	MemberCount int    `json:"memberCount"`
	Location    string `json:"location,omitempty"`
}

type OrganizerActivityItem struct {
	ID          string   `json:"id"`
	Type        string   `json:"type"`
	Description string   `json:"description"`
	Amount      *float64 `json:"amount,omitempty"`
	Date        string   `json:"date"`
	MemberName  string   `json:"memberName,omitempty"`
	GroupName   string   `json:"groupName,omitempty"`
}

func (h *Handler) registerOrganizerRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /companion/organizer/dashboard", h.HandleOrganizerDashboard)
}

// HandleOrganizerDashboard aggregates the organizer's active groups into the KPI
// summary. Real values (group/member counts, activity) come from Fineract; VSLA
// schedule fields default (mifos-bank-2 exposes no per-group meeting calendar yet).
func (h *Handler) HandleOrganizerDashboard(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	// Scope the dashboard to the CALLER's groups (resolveGroups), not every active group on the
	// instance. The previous path aggregated ALL groups — so a member saw myGroupCount=39 (the whole
	// instance) and it ran aggregateGroup+aggregateLoans ~39 times, making the first post-login screen
	// slow AND wrong. resolveGroups isolates the caller's groups (member-scoped via the userClientIDs
	// externalId match), so the KPIs reflect only groups the caller belongs to / organizes.
	fa := h.callerFromRequest(r)
	if fa == nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized", "missing or invalid session token")
		return
	}
	memberships, err := h.resolveGroups(fa)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "resolve groups: "+err.Error())
		return
	}

	// Personalize the greeting from the real logged-in user (bearer token), e.g.
	// "Welcome back, Rajan". Falls back to the generic "Organizer" only when the
	// caller can't be resolved — personalization is best-effort, never blocking.
	organizerName := "Organizer"
	if display := h.resolveDisplayName(fa); display != "" {
		if first, _ := splitName(display); first != "" {
			organizerName = first
		}
	}

	summary := OrganizerDashboardSummaryDto{
		OrganizerName:  organizerName,
		TodaySchedule:  []OrganizerScheduledMeeting{},
		RecentActivity: []OrganizerActivityItem{},
	}
	// Aggregate each of the caller's groups CONCURRENTLY — every group is an independent
	// aggregateGroup + aggregateLoans fan-out. Each goroutine writes only its own result slot; the
	// merge into the summary is done sequentially afterwards, preserving group order.
	type grpResult struct {
		ok          bool
		memberCount int
		schedule    OrganizerScheduledMeeting
		activity    []OrganizerActivityItem
	}
	results := make([]grpResult, len(memberships))
	var wg sync.WaitGroup
	for i, m := range memberships {
		wg.Add(1)
		go func(i int, m GroupMembership) {
			defer wg.Done()
			gid, perr := strconv.ParseInt(m.GroupID, 10, 64)
			if perr != nil {
				return
			}
			detail, members, aerr := h.aggregateGroup(gid)
			if aerr != nil {
				return
			}
			res := grpResult{
				ok:          true,
				memberCount: detail.MemberCount,
				// Meeting schedule is a VSLA-cadence default (WEEKLY) — mifos-bank-2 exposes no
				// per-group calendar yet; surface the real group + member count.
				schedule: OrganizerScheduledMeeting{
					GroupID:     m.GroupID,
					GroupName:   m.GroupName,
					MeetingTime: "14:00",
					MemberCount: detail.MemberCount,
				},
			}
			_, loanRows, _, _ := h.aggregateLoans(members)
			for _, row := range loanRows {
				res.activity = append(res.activity, OrganizerActivityItem{
					ID:          row.ID,
					Type:        row.Type,
					Description: row.Description,
					Amount:      row.Amount,
					Date:        row.Date,
					GroupName:   m.GroupName,
				})
			}
			results[i] = res
		}(i, m)
	}
	wg.Wait()
	for _, res := range results {
		if !res.ok {
			continue
		}
		summary.MyGroupCount++
		summary.TotalMembers += res.memberCount
		summary.TodaySchedule = append(summary.TodaySchedule, res.schedule)
		summary.RecentActivity = append(summary.RecentActivity, res.activity...)
	}
	summary.MeetingsTodayCount = len(summary.TodaySchedule)
	_ = json.NewEncoder(w).Encode(summary)
}
