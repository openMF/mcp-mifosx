// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-GRP: the group-dashboard read facade. It reshapes a real Fineract group
// (GET groups/{id}?associations=all + group/member accounts) into the nested
// contract the MifosSave (mifos-x-group-banking) app expects.
//
// The app does NOT call a single "dashboard" endpoint — its GroupDashboardApiImpl
// fans in FOUR sub-reads that each return one nested DTO:
//
//	GET /companion/groups/{groupId}          -> GroupDetailDto
//	GET /companion/groups/{groupId}/my-role  -> ViewerRoleInfoDto
//	GET /companion/groups/{groupId}/corpus   -> GroupCorpusDto
//	GET /companion/groups/{groupId}/accounts -> GroupAccountsDto
//
// and its GroupApiImpl lists groups via GET /companion/groups/mine -> GroupPageDto.
// We serve all of those (so the device renders real data) AND, as a convenience
// for callers who want the whole thing in one shot, the composite
// GET /companion/groups/{groupId}/dashboard -> GroupDashboardResponseDto and a
// GET /companion/groups alias of /mine.
//
// Field names + nesting + casing are matched EXACTLY to the app DTOs in
// core/network/.../model/{GroupDashboardDto,GroupDto,GroupTypeConfigDto}.kt —
// note typeConfig is snake_case on the wire while every other DTO is camelCase.
//
// Reads use the shared FineractClient service credential (adapter.DoRequest),
// which is correct for reading group data (unlike the auth path, which forwards
// end-user creds).
package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"sync"
	"time"
)

// registerGroupRoutes wires the COMP-GRP endpoints. Called from RegisterRoutes.
func (h *Handler) registerGroupRoutes(mux *http.ServeMux) {
	// Group list (what the app actually calls) + a bare alias per the task.
	mux.HandleFunc("GET /companion/groups/mine", h.HandleMyGroups)
	mux.HandleFunc("GET /companion/groups", h.HandleMyGroups)

	// Dashboard sub-reads (what GroupDashboardApiImpl fans in).
	mux.HandleFunc("GET /companion/groups/{groupId}", h.HandleGroupDetail)
	mux.HandleFunc("GET /companion/groups/{groupId}/my-role", h.HandleViewerRole)
	mux.HandleFunc("GET /companion/groups/{groupId}/corpus", h.HandleGroupCorpus)
	mux.HandleFunc("GET /companion/groups/{groupId}/accounts", h.HandleGroupAccounts)

	// Composite convenience aggregate.
	mux.HandleFunc("GET /companion/groups/{groupId}/dashboard", h.HandleGroupDashboard)
}

// ---- App-facing wire contract (exact match to the KMP DTOs) ----

// GroupInstanceConfigDto == typeConfig inside GroupDetailDto. SNAKE_CASE wire.
type GroupInstanceConfigDto struct {
	GroupType         string  `json:"group_type"`
	PoolModel         string  `json:"pool_model"`
	ContributionModel string  `json:"contribution_model"`
	ShareoutFormula   string  `json:"shareout_formula"`
	PayoutOrderMethod string  `json:"payout_order_method"`
	ShareValue        float64 `json:"share_value"`
	ContributionAmount float64 `json:"contribution_amount"`
	SocialFundEnabled bool    `json:"social_fund_enabled"`
	CycleLengthMonths int     `json:"cycle_length_months"`
	LoanMultiplier    float64 `json:"loan_multiplier"`
	InterestRate      float64 `json:"interest_rate"`
	FineAmount        float64 `json:"fine_amount"`
}

// GroupDetailDto == GET /companion/groups/{groupId}. This one endpoint backs TWO app
// consumers with different DTOs (both parsed with ignoreUnknownKeys, so one superset
// body satisfies both): GroupDashboardApiImpl.getGroupIdentity reads the camelCase
// detail fields (id/name/cycleNumber/typeConfig...), and InvitationApiImpl.getGroupPreview
// (join-with-code) reads GroupPreviewDto's REQUIRED fields (groupId/groupName/organizerName/
// memberCount/officeId + optional groupType/roleToAssign). The GroupPreview* fields below are
// the join-preview superset — omitting any REQUIRED one made the invitee's preview fail
// deserialization (SerializationException → the app's misleading "No internet connection").
type GroupDetailDto struct {
	ID                string                 `json:"id"`
	FineractCenterID  int64                  `json:"fineractCenterId"`
	Name              string                 `json:"name"`
	CycleNumber       int                    `json:"cycleNumber"`
	CycleLengthMonths int                    `json:"cycleLengthMonths"`
	MeetingFrequency  string                 `json:"meetingFrequency"`
	MemberCount       int                    `json:"memberCount"`
	OverdueLoansCount int                    `json:"overdueLoansCount"`
	Status            string                 `json:"status"`
	TypeConfig        GroupInstanceConfigDto `json:"typeConfig"`

	// GroupPreviewDto superset (join-with-code invitee preview) — same entity, second consumer.
	GroupID        int64  `json:"groupId"`
	GroupName      string `json:"groupName"`
	GroupType      string `json:"groupType"`
	OrganizerName  string `json:"organizerName"`
	OfficeID       int64  `json:"officeId"`
	RoleToAssign   string `json:"roleToAssign"`
}

// ViewerRoleInfoDto == GET /companion/groups/{groupId}/my-role.
type ViewerRoleInfoDto struct {
	Role     string `json:"role"`
	MemberID int64  `json:"memberId"`
}

// GroupCorpusDto == GET /companion/groups/{groupId}/corpus.
type GroupCorpusDto struct {
	CurrentBalance              float64  `json:"currentBalance"`
	OpeningBalance              float64  `json:"openingBalance"`
	TotalContributionsThisCycle float64  `json:"totalContributionsThisCycle"`
	TotalLoansOutstanding       float64  `json:"totalLoansOutstanding"`
	LastUpdated                 string   `json:"lastUpdated"`
	IsCycleEnd                  bool     `json:"isCycleEnd"`
	RotationPosition            *int     `json:"rotationPosition"`
	NextRecipientName           *string  `json:"nextRecipientName"`
	NextRecipientPosition       *int     `json:"nextRecipientPosition"`
}

// ActivityItemDto is one row of GroupAccountsDto.recentActivity.
type ActivityItemDto struct {
	ID          string   `json:"id"`
	Type        string   `json:"type"`
	Description string   `json:"description"`
	Amount      *float64 `json:"amount"`
	Date        string   `json:"date"`
	MemberName  *string  `json:"memberName"`
}

// GroupAccountsDto == GET /companion/groups/{groupId}/accounts.
type GroupAccountsDto struct {
	SavingsBalance     float64           `json:"savingsBalance"`
	LoansOutstanding   float64           `json:"loansOutstanding"`
	ActiveLoanCount    int               `json:"activeLoanCount"`
	ShareOutProjection *float64          `json:"shareOutProjection"`
	RecentActivity     []ActivityItemDto `json:"recentActivity"`
}

// GroupDashboardResponseDto == GET /companion/groups/{groupId}/dashboard.
type GroupDashboardResponseDto struct {
	Group      GroupDetailDto    `json:"group"`
	ViewerRole ViewerRoleInfoDto `json:"viewerRole"`
	Corpus     GroupCorpusDto    `json:"corpus"`
	Accounts   GroupAccountsDto  `json:"accounts"`
}

// GroupDto == one element of GroupPageDto.pageItems (group list).
type GroupDto struct {
	ID               string  `json:"id"`
	Name             string  `json:"name"`
	GroupType        string  `json:"groupType"`
	ViewerRole       string  `json:"viewerRole"`
	CycleNumber      int     `json:"cycleNumber"`
	MemberCount      int     `json:"memberCount"`
	LastMeetingDate  string  `json:"lastMeetingDate"`
	HealthIndicator  string  `json:"healthIndicator"`
	OverdueRate      float64 `json:"overdueRate"`
	Status           string  `json:"status"`
	// The app's grouplist GroupDto REQUIRES fineractGroupId (non-nullable Long) — a missing field
	// fails kotlinx deserialization ("Field 'fineractGroupId' is required ... but it was missing"),
	// which surfaced as the group-list screen's "Could not load groups". Group-centric model: the
	// group's own id IS the fineract group id. fineractCenterId is legacy (Center dropped) but kept
	// for back-compat.
	FineractGroupID  int64 `json:"fineractGroupId"`
	FineractCenterID int64 `json:"fineractCenterId"`
}

// GroupPageDto == GET /companion/groups[/mine].
type GroupPageDto struct {
	TotalFilteredRecords int        `json:"totalFilteredRecords"`
	PageItems            []GroupDto `json:"pageItems"`
}

// ---- Fineract wire types (only the fields we consume) ----

type fnStatus struct {
	Value  string `json:"value"`
	Active bool   `json:"active"`
}

type fnMember struct {
	ID          int64    `json:"id"`
	DisplayName string   `json:"displayName"`
	Status      fnStatus `json:"status"`
}

type fnGroup struct {
	ID             int64      `json:"id"`
	Name           string     `json:"name"`
	Status         fnStatus   `json:"status"`
	Active         bool       `json:"active"`
	ActivationDate []int      `json:"activationDate"`
	OfficeID       int64      `json:"officeId"`
	CenterID       *int64     `json:"centerId"`
	ClientMembers  []fnMember `json:"clientMembers"`
}

type fnGroupAccounts struct {
	SavingsAccounts []struct {
		ID             int64    `json:"id"`
		AccountBalance float64  `json:"accountBalance"`
		Status         fnStatus `json:"status"`
	} `json:"savingsAccounts"`
}

type fnClientAccounts struct {
	LoanAccounts []struct {
		ID          int64    `json:"id"`
		Status      fnStatus `json:"status"`
		LoanBalance float64  `json:"loanBalance"`
		InArrears   bool     `json:"inArrears"`
		ProductName string   `json:"productName"`
		Timeline    struct {
			ActualDisbursementDate []int `json:"actualDisbursementDate"`
		} `json:"timeline"`
	} `json:"loanAccounts"`
}

type fnSavingsTxn struct {
	ID              int64    `json:"id"`
	Amount          float64  `json:"amount"`
	Date            []int    `json:"date"`
	TransactionType struct {
		Deposit    bool `json:"deposit"`
		Withdrawal bool `json:"withdrawal"`
	} `json:"transactionType"`
}

// ---- Handlers ----

func (h *Handler) HandleMyGroups(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	// Return ONLY the caller's groups (member-scoped via resolveGroups), not every active group on
	// the instance. The previous path aggregated ALL active groups — with the seed's ~39 groups it
	// ran hundreds of sequential Fineract reads and TIMED OUT, returning nothing, so the app rendered
	// the zero-groups screen for a user who actually belongs to groups. resolveGroups already isolates
	// the caller's groups (and, with the userClientIDs phone/email fix, a member sees only theirs), so
	// /mine now aggregates a handful of cards and stays fast.
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
	// Aggregate the caller's group cards concurrently (bounded fan-out). On a per-group aggregation
	// error, fall back to a minimal card from the membership so the group still lists.
	cards := make([]*GroupDto, len(memberships))
	sem := make(chan struct{}, 8)
	var wg sync.WaitGroup
	for i, m := range memberships {
		wg.Add(1)
		go func(i int, m GroupMembership) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			card := GroupDto{
				ID:              m.GroupID,
				Name:            m.GroupName,
				GroupType:       "VSLA",
				ViewerRole:      m.Role,
				LastMeetingDate: m.JoinedAt,
				HealthIndicator: healthFor(0, 0),
				OverdueRate:     overdueRate(0, 0),
				Status:          "active",
			}
			if gid, perr := strconv.ParseInt(m.GroupID, 10, 64); perr == nil {
				card.FineractGroupID = gid
				if detail, _, aerr := h.aggregateGroup(gid); aerr == nil {
					card.CycleNumber = detail.CycleNumber
					card.MemberCount = detail.MemberCount
				}
			}
			cards[i] = &card
		}(i, m)
	}
	wg.Wait()
	page := GroupPageDto{PageItems: []GroupDto{}}
	for _, c := range cards {
		if c != nil {
			page.PageItems = append(page.PageItems, *c)
		}
	}
	page.TotalFilteredRecords = len(page.PageItems)
	_ = json.NewEncoder(w).Encode(page)
}

func (h *Handler) HandleGroupDetail(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	gid, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	detail, _, err := h.aggregateGroup(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	_ = json.NewEncoder(w).Encode(detail)
}

func (h *Handler) HandleViewerRole(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	if _, ok := groupIDParam(w, r); !ok {
		return
	}
	// No per-user member linkage on mifos-bank-2 -> the dashboard viewer is the
	// group organizer. memberId 0 = "not resolved to a member row" (staff caller).
	_ = json.NewEncoder(w).Encode(ViewerRoleInfoDto{Role: "ORGANIZER", MemberID: 0})
}

func (h *Handler) HandleGroupCorpus(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	gid, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	_, members, err := h.aggregateGroup(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	savings, _, err := h.aggregateSavings(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	loansOut, _, _, _ := h.aggregateLoans(members)
	_ = json.NewEncoder(w).Encode(buildCorpus(savings, loansOut))
}

func (h *Handler) HandleGroupAccounts(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	gid, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	_, members, err := h.aggregateGroup(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	accounts, err := h.buildAccounts(gid, members)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	_ = json.NewEncoder(w).Encode(accounts)
}

func (h *Handler) HandleGroupDashboard(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	gid, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	detail, members, err := h.aggregateGroup(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	// savings, loans and per-member accounts are independent given the member list — fetch them
	// concurrently so the dashboard costs ~one aggregation's latency instead of the sum (each
	// mifos-bank-2 round-trip is ~0.5-1.3s and these fan out per member).
	var (
		savings    float64
		savingsErr error
		loansOut   float64
		accounts   GroupAccountsDto
		accErr     error
		wg         sync.WaitGroup
	)
	wg.Add(3)
	go func() { defer wg.Done(); savings, _, savingsErr = h.aggregateSavings(gid) }()
	go func() { defer wg.Done(); loansOut, _, _, _ = h.aggregateLoans(members) }()
	go func() { defer wg.Done(); accounts, accErr = h.buildAccounts(gid, members) }()
	wg.Wait()
	if savingsErr != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", savingsErr.Error())
		return
	}
	if accErr != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", accErr.Error())
		return
	}
	_ = json.NewEncoder(w).Encode(GroupDashboardResponseDto{
		Group:      detail,
		ViewerRole: ViewerRoleInfoDto{Role: "ORGANIZER", MemberID: 0},
		Corpus:     buildCorpus(savings, loansOut),
		Accounts:   accounts,
	})
}

// ---- Aggregation (real Fineract -> app shape) ----

// memberRef is a lightweight member reference reused across loan aggregation.
type memberRef struct {
	ID   int64
	Name string
}

// aggregateGroup reads groups/{id}?associations=all and maps it to GroupDetailDto.
// VSLA-specific fields Fineract does not model are filled with the computed
// defaults documented inline (never member-facing financials).
func (h *Handler) aggregateGroup(groupID int64) (GroupDetailDto, []memberRef, error) {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("groups/%d", groupID), nil, map[string]string{"associations": "all"})
	if err != nil {
		return GroupDetailDto{}, nil, fmt.Errorf("fineract get group %d: %s", groupID, string(nonEmpty(raw)))
	}
	var g fnGroup
	if err := json.Unmarshal(raw, &g); err != nil {
		return GroupDetailDto{}, nil, fmt.Errorf("decode group %d: %w", groupID, err)
	}
	members := make([]memberRef, 0, len(g.ClientMembers))
	for _, m := range g.ClientMembers {
		members = append(members, memberRef{ID: m.ID, Name: m.DisplayName})
	}
	_, _, activeLoans, overdue := h.aggregateLoans(members)

	detail := GroupDetailDto{
		ID:                strconv.FormatInt(g.ID, 10),
		FineractCenterID:  deref(g.CenterID),
		Name:              g.Name,
		CycleNumber:       1,  // computed default: fresh VSLA seed is on its first cycle
		CycleLengthMonths: 12, // computed default: standard 12-month VSLA cycle
		MeetingFrequency:  "WEEKLY",
		MemberCount:       len(g.ClientMembers),
		OverdueLoansCount: overdue,
		Status:            statusValue(g.Status),
		TypeConfig:        defaultVSLAConfig(),

		// GroupPreviewDto superset — join-with-code invitee preview (see GroupDetailDto kdoc).
		GroupID:       g.ID,
		GroupName:     g.Name,
		GroupType:     "VSLA",             // matches GroupTypeSlugDto.VSLA (default seed type)
		OrganizerName: "Group Organizer",  // mifos-bank-2 exposes no per-group staff name yet
		OfficeID:      g.OfficeID,
		RoleToAssign:  "MEMBER",           // invitees join as members (GroupRoleDto.MEMBER)
	}
	_ = activeLoans
	return detail, members, nil
}

// aggregateSavings sums active group savings balances (the real corpus).
func (h *Handler) aggregateSavings(groupID int64) (total float64, ids []int64, err error) {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("groups/%d/accounts", groupID), nil, nil)
	if err != nil {
		return 0, nil, fmt.Errorf("fineract group accounts %d: %s", groupID, string(nonEmpty(raw)))
	}
	var ga fnGroupAccounts
	if err := json.Unmarshal(raw, &ga); err != nil {
		return 0, nil, fmt.Errorf("decode group accounts %d: %w", groupID, err)
	}
	for _, s := range ga.SavingsAccounts {
		if s.Status.Active {
			total += s.AccountBalance
			ids = append(ids, s.ID)
		}
	}
	return total, ids, nil
}

// aggregateLoans sums active member-loan outstanding across the group's members.
// Group savings/accounts does not surface members' individual loans, so we walk
// each member's clients/{id}/accounts — real, per-member loan balances.
func (h *Handler) aggregateLoans(members []memberRef) (outstanding float64, activityRows []ActivityItemDto, activeCount int, overdue int) {
	for _, m := range members {
		raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("clients/%d/accounts", m.ID), nil, nil)
		if err != nil {
			continue
		}
		var ca fnClientAccounts
		if err := json.Unmarshal(raw, &ca); err != nil {
			continue
		}
		name := m.Name
		for _, l := range ca.LoanAccounts {
			if !l.Status.Active {
				continue
			}
			outstanding += l.LoanBalance
			activeCount++
			if l.InArrears {
				overdue++
			}
			amt := l.LoanBalance
			nm := name
			activityRows = append(activityRows, ActivityItemDto{
				ID:          fmt.Sprintf("loan-%d", l.ID),
				Type:        "LOAN",
				Description: fmt.Sprintf("Loan disbursed to %s (%s)", name, l.ProductName),
				Amount:      &amt,
				Date:        fmtFineractDate(l.Timeline.ActualDisbursementDate),
				MemberName:  &nm,
			})
		}
	}
	return outstanding, activityRows, activeCount, overdue
}

// buildAccounts assembles GroupAccountsDto with a real recent-activity feed
// (savings deposits + loan disbursements).
func (h *Handler) buildAccounts(groupID int64, members []memberRef) (GroupAccountsDto, error) {
	savings, savingsIDs, err := h.aggregateSavings(groupID)
	if err != nil {
		return GroupAccountsDto{}, err
	}
	loansOut, loanActivity, activeLoans, _ := h.aggregateLoans(members)

	activity := make([]ActivityItemDto, 0)
	for _, sid := range savingsIDs {
		activity = append(activity, h.savingsActivity(sid)...)
	}
	activity = append(activity, loanActivity...)
	// Newest first; cap to the 10 most recent.
	sort.SliceStable(activity, func(i, j int) bool { return activity[i].Date > activity[j].Date })
	if len(activity) > 10 {
		activity = activity[:10]
	}

	// shareOutProjection: at cycle-end an accumulating VSLA shares out the corpus
	// net of outstanding loans. Computed from real balances, not fabricated.
	proj := savings - loansOut
	if proj < 0 {
		proj = 0
	}
	return GroupAccountsDto{
		SavingsBalance:     savings,
		LoansOutstanding:   loansOut,
		ActiveLoanCount:    activeLoans,
		ShareOutProjection: &proj,
		RecentActivity:     activity,
	}, nil
}

// savingsActivity maps a savings account's deposit transactions to activity rows.
// Fineract exposes the transaction list only via the account read with
// associations=transactions (the /transactions path is POST-only).
func (h *Handler) savingsActivity(savingsID int64) []ActivityItemDto {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("savingsaccounts/%d", savingsID), nil, map[string]string{"associations": "transactions"})
	if err != nil {
		return nil
	}
	var acct struct {
		Transactions []fnSavingsTxn `json:"transactions"`
	}
	if err := json.Unmarshal(raw, &acct); err != nil {
		return nil
	}
	rows := make([]ActivityItemDto, 0, len(acct.Transactions))
	for _, t := range acct.Transactions {
		if !t.TransactionType.Deposit {
			continue // app ActivityTypeDto has no WITHDRAWAL member; deposits only
		}
		amt := t.Amount
		rows = append(rows, ActivityItemDto{
			ID:          fmt.Sprintf("savings-%d-%d", savingsID, t.ID),
			Type:        "DEPOSIT",
			Description: "Group savings deposit",
			Amount:      &amt,
			Date:        fmtFineractDate(t.Date),
		})
	}
	return rows
}

// buildCorpus derives GroupCorpusDto from the real savings + loan totals.
func buildCorpus(savings, loansOut float64) GroupCorpusDto {
	return GroupCorpusDto{
		CurrentBalance:              savings,
		OpeningBalance:              0, // computed default: cycle 1 opened at zero corpus
		TotalContributionsThisCycle: savings,
		TotalLoansOutstanding:       loansOut,
		LastUpdated:                 time.Now().UTC().Format(time.RFC3339),
		IsCycleEnd:                  false,
		// Accumulating VSLA -> no fixed rotation payout; rotation fields stay null.
		RotationPosition:      nil,
		NextRecipientName:     nil,
		NextRecipientPosition: nil,
	}
}

// defaultVSLAConfig is the computed VSLA rule-set for a group whose type Fineract
// does not model. These are governance defaults, not member-facing balances.
func defaultVSLAConfig() GroupInstanceConfigDto {
	return GroupInstanceConfigDto{
		GroupType:          "VSLA",              // GroupTypeSlugDto.VSLA
		PoolModel:          "ACCUMULATING",      // SavingsMechanismDto.ACCUMULATING
		ContributionModel:  "FIXED_AMOUNT",      // GroupContributionModelDto.FIXED_AMOUNT
		ShareoutFormula:    "PRO_RATA_BY_SHARES",
		PayoutOrderMethod:  "NONE",
		ShareValue:         100,
		ContributionAmount: 100,
		SocialFundEnabled:  true,
		CycleLengthMonths:  12,
		LoanMultiplier:     3,
		InterestRate:       10,
		FineAmount:         50,
	}
}

// ---- small helpers ----

func groupIDParam(w http.ResponseWriter, r *http.Request) (int64, bool) {
	raw := r.PathValue("groupId")
	id, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || id <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid groupId")
		return 0, false
	}
	return id, true
}

func statusValue(s fnStatus) string {
	if s.Value == "" {
		return "Active"
	}
	return s.Value
}

func deref(p *int64) int64 {
	if p == nil {
		return 0
	}
	return *p
}

func healthFor(overdue, activeLoans int) string {
	if overdue == 0 {
		return "GREEN"
	}
	if overdue < activeLoans {
		return "AMBER"
	}
	return "RED"
}

func overdueRate(overdue, activeLoans int) float64 {
	if activeLoans == 0 {
		return 0
	}
	return float64(overdue) / float64(activeLoans)
}

// fmtFineractDate turns Fineract's [year, month, day] array into YYYY-MM-DD.
func fmtFineractDate(parts []int) string {
	if len(parts) < 3 {
		return ""
	}
	return fmt.Sprintf("%04d-%02d-%02d", parts[0], parts[1], parts[2])
}

// fmtFineractInstant renders a Fineract [y,m,d] date as a full ISO-8601 instant
// (midnight UTC). The MifosSave app parses membership joinedAt with kotlinx
// Instant.parse, which rejects a date-only string.
func fmtFineractInstant(parts []int) string {
	if len(parts) < 3 {
		return "2026-01-01T00:00:00Z"
	}
	return fmt.Sprintf("%04d-%02d-%02dT00:00:00Z", parts[0], parts[1], parts[2])
}
