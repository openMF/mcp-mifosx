// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-CAL: the meeting-lifecycle facade for the MifosSave (mifos-x-group-banking)
// app's FOUR meeting features. The app's shared HttpClient base URL points at THIS
// Go server (companion), so every path below is one the app fires; the Go server
// serves only explicitly-registered routes (there is no catch-all Fineract proxy),
// so each is built here against real mifos-bank-2 data.
//
// A meeting lives on the CENTER model (m_center) — plain groups have no meeting
// schedule. A Fineract center owns a COLLECTION calendar whose recurrence generates
// the scheduled meeting dates; the group(s) under the center own the client members
// whose attendance is recorded. Two custom multiRow datatables persist a conducted
// meeting: dt_meeting_record (apptable m_center — one row per meeting) and
// dt_meeting_attendance (apptable m_client — one row per member per meeting).
//
// Path prefixing mirrors the app's OWN services (each reuses the shared client, so
// the prefix is baked into each service's path string, not the base URL):
//
//   meeting-calendar     (MeetingApiImpl)            -> /fineract-provider/api/v1/…
//   meeting-summary      (MeetingRecordApiImpl)      -> /fineract-provider/api/v1/…
//   previous-meeting-rev (MeetingAttendanceApiImpl)  -> /fineract-provider/api/v1/…
//   meeting-conduct      (MeetingConductApiImpl)     -> bare /…  (no prefix)
//
// so this facade registers BOTH the prefixed and the bare variants of the
// dt_meeting_record read (they carry different response shapes — see below).
//
// ENDPOINTS (app path -> handler -> app DTO, source in APP core/network/.../service):
//   GET /fineract-provider/api/v1/centers/{centerId}/meetings
//       -> HandleCenterMeetings           -> []MeetingListItemDto     (meetingcalendar/MeetingApi.getCenterMeetings)
//   GET /fineract-provider/api/v1/datatables/dt_meeting_record/{centerId}[?meetingNumber=N]
//       -> HandleMeetingRecordDatatable   -> MeetingRecordListDto (no q) | MeetingSummaryRecordDto (q)
//          (meetingcalendar/MeetingApi.getMeetingRecords | meetingsummary/MeetingRecordApi.getMeetingRecord)
//   GET /fineract-provider/api/v1/datatables/dt_meeting_attendance/{meetingId}
//       -> HandleMeetingAttendanceList    -> []MeetingAttendanceRowDto (previousmeetingreview/MeetingAttendanceApi)
//   GET /datatables/dt_meeting_record/{centerId}
//       -> HandlePreviousMeetingRecord    -> MeetingRecordDetailDto    (meetingconduct.getPreviousMeetingRecord)
//   GET /centers/{centerId}
//       -> HandleCenterDetail             -> CenterDetailDto           (meetingconduct.getGroupMembers)
//   GET /datatables/dt_group_corpus/{centerId}
//       -> HandleGroupCorpusRead          -> CorpusRecordDto           (meetingconduct.getGroupCorpus)
//   GET /loans?groupId=&loanStatus=active
//       -> HandleActiveLoans              -> LoanListResponseDto        (meetingconduct.getActiveLoans)
//   GET /datatables/dt_loan_vote/{loanId}
//       -> HandleLoanVotes                -> LoanVoteRecordDto          (meetingconduct.getLoanVotes)
//   POST /datatables/dt_meeting_record            -> HandlePostMeetingRecord      -> DataTableEntryResponseDto
//   POST /datatables/dt_meeting_attendance        -> HandlePostMeetingAttendance  -> DataTableEntryResponseDto
//   POST /savingsaccounts/{savingsId}/transactions?command=deposit -> HandlePostSavingsTransaction
//   PUT  /datatables/dt_group_corpus/{centerId}   -> HandleUpdateCorpus           -> DataTableEntryResponseDto
//   PUT  /centers/{centerId}/calendars/{calendarId}?command=updateCalendar -> HandleRescheduleCalendar
//
// NOT registered here (deliberate):
//   POST /loans/{loanId}/transactions?command=repayment — already served by loan_write.go's
//     HandleLoanTransaction (Go 1.22 ServeMux would panic on a duplicate pattern); the
//     meeting-conduct repayment (priority 4) reuses that existing handler verbatim.
//   POST /loans/{loanId}/transactions?command=disburse — never issued by the app:
//     MeetingConductRepositoryImpl derives disbursals from pendingLoanApplications, which is
//     ALWAYS emptyList() (CFF1 gap — no api.yaml endpoint loads it), so the disburse branch
//     is dead. Left unserved rather than editing loan_write.go's existing handler.
//
// DATE-FORMAT decisions (checked against each app mapper + the live Fineract column types):
//   - dt_meeting_record.meeting_date + dt_group_corpus.last_updated_date are real Fineract
//     DATE columns. The app's CreateMeetingRecordRequestDto/UpdateCorpusRequestDto carry
//     dateFormat="dd MMMM yyyy", but their actualDate value is unreliable — the conduct
//     ViewModel sets actualDate = previousMeetingSummary?.date.orEmpty() (the PRIOR meeting's
//     date string, or "" on the first meeting; MeetingConductViewModel.kt:560). So this facade
//     IGNORES the app's dateFormat and normalizes whatever it receives (ISO, "dd MMMM yyyy",
//     or empty->today) to canonical "yyyy-MM-dd" via normalizeFineractDate, then writes with
//     dateFormat="yyyy-MM-dd"+locale="en". On READ the DATE column comes back as a Fineract
//     [y,m,d] array, reshaped to "YYYY-MM-DD" via fmtFineractDate — the string the app then
//     round-trips and displays (MeetingListItem/MeetingSummaryData/PreviousMeetingSummary all
//     treat the date as an opaque display String, no kotlinx parse).
//   - dt_meeting_attendance has NO date column (meeting_number keys the row) — the app's
//     CreateAttendanceRequestDto sends no date; the write is locale-only.
//   - the savings-deposit transactionDate IS a Fineract command DATE field parsed as
//     date-only "dd MMMM yyyy" — HandlePostSavingsTransaction normalizes it via fineractTxnDate
//     (share_out_write.go) exactly like the share-out withdrawal.
//   - the reschedule updateCalendar startDate is a Fineract DATE — dateFormat/locale injected
//     if the caller omitted them.
//
// meetingId semantics: this facade DEFINES meetingId = "{centerId}-{meetingNumber}" in the
// calendar list it emits AND PARSES it back in the attendance read — the app carries it
// opaquely through navigation (MeetingCalendarRoute -> conduct/review nav args), never parsing
// it, so the two ends are the sole owners of the format.
//
// Reads/writes use the shared FineractClient service credential (adapter.DoRequest) — the
// correct path for an organizer/staff meeting operation, exactly like groups.go / share_out_write.go.
package companion

import (
	"encoding/json"
	"fmt"
	"math"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	meetingRecordTable     = "dt_meeting_record"
	meetingAttendanceTable = "dt_meeting_attendance"
	groupCorpusTable       = "dt_group_corpus"
	loanVoteTable          = "dt_loan_vote"
	centerApptable         = "m_center"
	clientApptable         = "m_client"
	fineractV1Prefix       = "/fineract-provider/api/v1"

	// upcomingMeetingCap bounds the meetings list: all past/completed occurrences plus this
	// many future ones (the app pins one upcoming card + lists the past). A weekly calendar
	// otherwise yields 100+ future recurringDates.
	upcomingMeetingCap = 4
)

// registerMeetingRoutes wires all COMP-CAL meeting-lifecycle endpoints. Called from
// RegisterRoutes. See the file header for the full path/handler/DTO map and the
// deliberately-unregistered loan-transaction paths.
func (h *Handler) registerMeetingRoutes(mux *http.ServeMux) {
	// meeting-calendar / meeting-summary / previous-meeting-review — prefixed base.
	mux.HandleFunc("GET "+fineractV1Prefix+"/centers/{centerId}/meetings", h.HandleCenterMeetings)
	// meeting-calendar schedule read (AL-RULE dt_meeting_schedule on m_group, keyed by groupId).
	mux.HandleFunc("GET "+fineractV1Prefix+"/datatables/dt_meeting_schedule/{groupId}", h.HandleMeetingSchedule)
	mux.HandleFunc("GET "+fineractV1Prefix+"/datatables/dt_meeting_record/{centerId}", h.HandleMeetingRecordDatatable)
	mux.HandleFunc("GET "+fineractV1Prefix+"/datatables/dt_meeting_attendance/{meetingId}", h.HandleMeetingAttendanceList)

	// meeting-conduct — bare base.
	mux.HandleFunc("GET /datatables/dt_meeting_record/{centerId}", h.HandlePreviousMeetingRecord)
	mux.HandleFunc("GET /centers/{centerId}", h.HandleCenterDetail)
	mux.HandleFunc("GET /datatables/dt_group_corpus/{centerId}", h.HandleGroupCorpusRead)
	mux.HandleFunc("GET /loans", h.HandleActiveLoans)
	mux.HandleFunc("GET /datatables/dt_loan_vote/{loanId}", h.HandleLoanVotes)
	mux.HandleFunc("POST /datatables/dt_meeting_record", h.HandlePostMeetingRecord)
	mux.HandleFunc("POST /datatables/dt_meeting_attendance", h.HandlePostMeetingAttendance)
	mux.HandleFunc("POST /savingsaccounts/{savingsId}/transactions", h.HandlePostSavingsTransaction)
	mux.HandleFunc("PUT /datatables/dt_group_corpus/{centerId}", h.HandleUpdateCorpus)

	// reschedule (G3/F6) — server-gated in the app (offline-queued), built here for when it lands.
	mux.HandleFunc("PUT /centers/{centerId}/calendars/{calendarId}", h.HandleRescheduleCalendar)
}

// ---- App-facing wire contract (exact @SerialName match to the KMP DTOs) ----

// meetingListItemDto == MeetingListItemDto (meetingcalendar). attendanceCount /
// totalCollectedKES are pointers so UPCOMING/MISSED rows serialize null (matching the
// app's `Int? = null` / `Long? = null`).
type meetingListItemDto struct {
	MeetingID       string `json:"meetingId"`
	MeetingNumber   int    `json:"meetingNumber"`
	MeetingDate     string `json:"meetingDate"`
	Status          string `json:"status"`
	AttendanceCount *int   `json:"attendanceCount"`
	TotalCollected  *int64 `json:"totalCollectedKES"`
}

// meetingRecordItemDto == MeetingRecordItemDto (one row of MeetingRecordListDto).
type meetingRecordItemDto struct {
	MeetingNumber   int    `json:"meetingNumber"`
	ActualDate      string `json:"actualDate"`
	TotalSavings    int64  `json:"totalSavings"`
	TotalRepayments int64  `json:"totalRepayments"`
	AttendanceCount int    `json:"attendanceCount"`
	ClosingCorpus   int64  `json:"closingCorpus"`
}

// meetingRecordListDto == MeetingRecordListDto (meetingcalendar.getMeetingRecords).
type meetingRecordListDto struct {
	CenterID int                    `json:"centerId"`
	Records  []meetingRecordItemDto `json:"records"`
}

// savingsBreakdownItemDto == SavingsBreakdownItemDto (nested in MeetingSummaryRecordDto).
type savingsBreakdownItemDto struct {
	MemberID          string `json:"memberId"`
	MemberName        string `json:"memberName"`
	GroupSavings      int64  `json:"groupSavings"`
	IndividualSavings int64  `json:"individualSavings"`
}

// loanSummaryItemDto == LoanSummaryItemDto (nested in MeetingSummaryRecordDto).
type loanSummaryItemDto struct {
	MemberID        string `json:"memberId"`
	MemberName      string `json:"memberName"`
	AmountDisbursed int64  `json:"amountDisbursed"`
	AmountRepaid    int64  `json:"amountRepaid"`
	OutstandingAfter int64 `json:"outstandingAfter"`
}

// meetingSummaryRecordDto == MeetingSummaryRecordDto (meetingsummary.getMeetingRecord).
type meetingSummaryRecordDto struct {
	MeetingID                  string                    `json:"meetingId"`
	MeetingNumber              int                       `json:"meetingNumber"`
	ActualDate                 string                    `json:"actualDate"`
	AttendanceCount            int                       `json:"attendanceCount"`
	TotalMemberCount           int                       `json:"totalMemberCount"`
	GroupSavingsCollected      int64                     `json:"groupSavingsCollected"`
	IndividualSavingsCollected int64                     `json:"individualSavingsCollected"`
	TotalSavingsCollected      int64                     `json:"totalSavingsCollected"`
	LoansDisbursed             int64                     `json:"loansDisbursed"`
	LoansRepaid                int64                     `json:"loansRepaid"`
	FinesCollected             int64                     `json:"finesCollected"`
	OpeningCorpus              int64                     `json:"openingCorpus"`
	ClosingCorpus              int64                     `json:"closingCorpus"`
	SavingsBreakdown           []savingsBreakdownItemDto `json:"savingsBreakdown"`
	LoanItems                  []loanSummaryItemDto      `json:"loanItems"`
}

// meetingAttendanceRowDto == MeetingAttendanceRowDto (previousmeetingreview).
type meetingAttendanceRowDto struct {
	MemberID   string `json:"memberId"`
	MemberName string `json:"memberName"`
	Status     string `json:"status"`
	FineAmount int64  `json:"fineAmount"`
}

// meetingRecordDetailDto == MeetingRecordDetailDto (meetingconduct.getPreviousMeetingRecord).
type meetingRecordDetailDto struct {
	MeetingID           string `json:"meetingId"`
	MeetingNumber       int    `json:"meetingNumber"`
	ActualDate          string `json:"actualDate"`
	TotalSavings        int64  `json:"totalSavings"`
	TotalRepayments     int64  `json:"totalRepayments"`
	TotalLoansDisbursed int64  `json:"totalLoansDisbursed"`
	TotalFinesCollected int64  `json:"totalFinesCollected"`
	ClosingCorpus       int64  `json:"closingCorpus"`
	AttendanceCount     int    `json:"attendanceCount"`
}

// clientMemberDto == ClientMemberDto (nested in CenterDetailDto).
type clientMemberDto struct {
	ID               int64   `json:"id"`
	DisplayName      string  `json:"displayName"`
	Role             string  `json:"role"`
	SavingsAccountID *string `json:"savingsAccountId"`
}

// centerDetailDto == CenterDetailDto (meetingconduct.getGroupMembers).
type centerDetailDto struct {
	ID                  int               `json:"id"`
	Name                string            `json:"name"`
	ActiveClientMembers []clientMemberDto `json:"activeClientMembers"`
}

// corpusRecordDto == CorpusRecordDto (meetingconduct.getGroupCorpus).
type corpusRecordDto struct {
	CenterID           int    `json:"centerId"`
	CorpusBalance      int64  `json:"corpusBalance"`
	CashOnHand         int64  `json:"cashOnHand"`
	LastUpdatedMeeting int    `json:"lastUpdatedMeeting"`
	LastUpdatedDate    string `json:"lastUpdatedDate"`
}

// meetingActiveLoanDto == MeetingActiveLoanDto (one row of LoanListResponseDto).
type meetingActiveLoanDto struct {
	LoanID                  string `json:"loanId"`
	ClientID                string `json:"clientId"`
	ClientName              string `json:"clientName"`
	Principal               int64  `json:"principal"`
	OutstandingBalance      int64  `json:"outstandingBalance"`
	IsOverdue               bool   `json:"isOverdue"`
	WeekNumber              int    `json:"weekNumber"`
	NumberOfRepayments      int    `json:"numberOfRepayments"`
	ExpectedWeeklyRepayment int64  `json:"expectedWeeklyRepayment"`
}

// loanListResponseDto == LoanListResponseDto (meetingconduct.getActiveLoans).
type loanListResponseDto struct {
	TotalFilteredRecords int                    `json:"totalFilteredRecords"`
	PageItems            []meetingActiveLoanDto `json:"pageItems"`
}

// loanVoteRecordDto == LoanVoteRecordDto (meetingconduct.getLoanVotes).
type loanVoteRecordDto struct {
	LoanID       string `json:"loanId"`
	VotesFor     int    `json:"votesFor"`
	VotesAgainst int    `json:"votesAgainst"`
	VotesAbstain int    `json:"votesAbstain"`
}

// dataTableEntryResponseDto == DataTableEntryResponseDto (every meeting write response).
type dataTableEntryResponseDto struct {
	ResourceID         int64  `json:"resourceId"`
	ResourceIdentifier string `json:"resourceIdentifier"`
}

// ---- App-facing write request bodies (camelCase, as the app POSTs them) ----

type createMeetingRecordRequestIn struct {
	// The app's CreateMeetingRecordRequestDto serializes the group scope as `groupId` (Group-centric
	// model — m_group IS the center); older callers sent `centerId`. Accept BOTH so the submit's
	// priority-1 record write is never rejected (a missing scope 400'd the whole submit → offline
	// fallthrough → empty meeting-summary). i64() prefers whichever is set.
	CenterID                int    `json:"centerId"`
	GroupID                 int    `json:"groupId"`
	MeetingNumber           int    `json:"meetingNumber"`
	ActualDate              string `json:"actualDate"`
	OpeningCorpus           int64  `json:"openingCorpus"`
	ClosingCorpus           int64  `json:"closingCorpus"`
	TotalSavingsCollected   int64  `json:"totalSavingsCollected"`
	TotalRepaymentsReceived int64  `json:"totalRepaymentsReceived"`
	TotalLoansDisbursed     int64  `json:"totalLoansDisbursed"`
	TotalFinesCollected     int64  `json:"totalFinesCollected"`
	AttendanceCount         int    `json:"attendanceCount"`
}

type createAttendanceRequestIn struct {
	MeetingID  string `json:"meetingId"`
	MemberID   string `json:"memberId"`
	Status     string `json:"status"`
	FineAmount int64  `json:"fineAmount"`
	// SavingsCollected / RepaymentAmount are NOT part of the app's CreateAttendanceRequestDto
	// (the app posts per-member savings as separate savingsaccounts transactions). They are
	// accepted here as optional enrichers so a caller CAN populate the per-member breakdown the
	// meeting-summary read surfaces; nil (the app case) writes the column null.
	SavingsCollected *int64 `json:"savingsCollected"`
	RepaymentAmount  *int64 `json:"repaymentAmount"`
}

type savingsTransactionRequestIn struct {
	TransactionDate   string `json:"transactionDate"`
	TransactionAmount int64  `json:"transactionAmount"`
	PaymentTypeID     int    `json:"paymentTypeId"`
}

type updateCorpusRequestIn struct {
	CorpusBalance      int64  `json:"corpusBalance"`
	LastUpdatedMeeting int    `json:"lastUpdatedMeeting"`
	LastUpdatedDate    string `json:"lastUpdatedDate"`
}

// ---- Fineract wire types (only the fields consumed) ----

// fnMeetingRecordRow is one row of dt_meeting_record as read back (snake_case columns +
// Fineract-added id/center_id/created_at/updated_at). meeting_date is a [y,m,d] DATE array.
type fnMeetingRecordRow struct {
	ID                      int64   `json:"id"`
	MeetingNumber           int     `json:"meeting_number"`
	MeetingDate             []int   `json:"meeting_date"`
	OpeningBalance          float64 `json:"opening_balance"`
	ClosingBalance          float64 `json:"closing_balance"`
	TotalSavingsCollected   float64 `json:"total_savings_collected"`
	TotalRepaymentsReceived float64 `json:"total_repayments_received"`
	TotalLoansDisbursed     float64 `json:"total_loans_disbursed"`
	TotalFinesCollected     float64 `json:"total_fines_collected"`
	AttendanceCount         int     `json:"attendance_count"`
}

// fnAttendanceRow is one row of dt_meeting_attendance (apptable m_client).
type fnAttendanceRow struct {
	ID               int64   `json:"id"`
	MeetingNumber    int     `json:"meeting_number"`
	Status           string  `json:"status"`
	FineAmount       float64 `json:"fine_amount"`
	SavingsCollected float64 `json:"savings_collected"`
	RepaymentAmount  float64 `json:"repayment_amount"`
}

// fnCorpusRow is the single row of dt_group_corpus.
type fnCorpusRow struct {
	CorpusBalance      float64 `json:"corpus_balance"`
	CashOnHand         float64 `json:"cash_on_hand"`
	LastUpdatedMeeting int     `json:"last_updated_meeting"`
	LastUpdatedDate    []int   `json:"last_updated_date"`
}

// fnLoanVoteRow is the single row of dt_loan_vote.
type fnLoanVoteRow struct {
	VotesFor     int `json:"votes_for"`
	VotesAgainst int `json:"votes_against"`
	VotesAbstain int `json:"votes_abstain"`
}

// fnCalendar is one element of GET /centers/{id}/calendars.
type fnCalendar struct {
	ID            int64  `json:"id"`
	Title         string `json:"title"`
	StartDate     string `json:"startDate"`
	Type          struct {
		Value string `json:"value"`
	} `json:"type"`
	RecurringDates [][]int `json:"recurringDates"`
}

// ---- Handlers: meeting-calendar ----

// HandleCenterMeetings builds the scheduled-meetings list from the center's COLLECTION
// calendar recurrence (the real source — Fineract's native /centers/{id}/meetings only
// lists m_meeting rows created by a saved collection sheet, so it is empty until a sheet
// is generated) enriched with the dt_meeting_record financial rows for COMPLETED meetings.
// resolveMeetingCenter maps an id the app passes as a "centerId" to the real
// Fineract center id. The app derives it as groupId.toInt (documented drift), so
// if GET groups/{id} exposes a centerId we use that; otherwise the id is already a
// center and is returned unchanged.
func (h *Handler) resolveMeetingCenter(id int64) int64 {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("groups/%d", id), nil, nil)
	if err != nil {
		return id
	}
	var g struct {
		CenterID int64 `json:"centerId"`
	}
	if json.Unmarshal(raw, &g) == nil && g.CenterID > 0 {
		return g.CenterID
	}
	return id
}

func (h *Handler) HandleCenterMeetings(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	centerID, ok := pathInt64Param(w, r, "centerId")
	if !ok {
		return
	}
	// The MifosSave app navigates meeting-calendar with centerId = groupId.toInt
	// (a documented app-side drift — GroupBankingNavHost flags "group-dashboard has
	// no centerId; bridge by parsing groupId"). Resolve that groupId to its real
	// Fineract centerId so the schedule of the group's meeting center is served.
	centerID = h.resolveMeetingCenter(centerID)
	out, err := h.buildMeetingSchedule(centerID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	_ = json.NewEncoder(w).Encode(out)
}

// HandleMeetingSchedule (meetingcalendar.getMeetingSchedule) serves the group's meeting schedule
// from the AL-RULE dt_meeting_schedule surface (GET /…/datatables/dt_meeting_schedule/{groupId}).
// It reuses the SAME schedule the meetings list is built from (calendar recurrence enriched with
// completed dt_meeting_record rows, with the synthesized-weekly fallback for companion VSLA groups),
// so a group whose dt_meeting_schedule is not yet provisioned still returns a conductable schedule
// rather than an empty list — the app expects List<MeetingListItemDto>.
func (h *Handler) HandleMeetingSchedule(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	groupID, ok := pathInt64Param(w, r, "groupId")
	if !ok {
		return
	}
	centerID := h.resolveMeetingCenter(groupID)
	out, err := h.buildMeetingSchedule(centerID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	_ = json.NewEncoder(w).Encode(out)
}

// buildMeetingSchedule assembles the []MeetingListItemDto schedule for a center: the calendar
// recurrence dates (synthesized weekly when none) each classified COMPLETED (a dt_meeting_record row
// exists) / MISSED (past, no record) / UPCOMING (future), capped at upcomingMeetingCap future slots.
// Shared by HandleCenterMeetings (meetings list) and HandleMeetingSchedule (schedule read).
func (h *Handler) buildMeetingSchedule(centerID int64) ([]meetingListItemDto, error) {
	dates, err := h.centerMeetingDates(centerID)
	if err != nil {
		return nil, err
	}
	records, _ := h.readMeetingRecords(centerID) // tolerant: no records yet -> all UPCOMING/MISSED
	recByNumber := make(map[int]fnMeetingRecordRow, len(records))
	for _, rec := range records {
		recByNumber[rec.MeetingNumber] = rec
	}

	today := todayYMD()
	out := make([]meetingListItemDto, 0, len(dates))
	upcomingSeen := 0
	for i, d := range dates {
		meetingNumber := i + 1
		item := meetingListItemDto{
			MeetingID:     meetingKey(centerID, meetingNumber),
			MeetingNumber: meetingNumber,
			MeetingDate:   fmtFineractDate(d),
		}
		if rec, done := recByNumber[meetingNumber]; done {
			item.Status = "COMPLETED"
			ac := rec.AttendanceCount
			total := roundKES(rec.TotalSavingsCollected) + roundKES(rec.TotalRepaymentsReceived)
			item.AttendanceCount = &ac
			item.TotalCollected = &total
		} else if ymdBefore(d, today) {
			item.Status = "MISSED"
		} else {
			item.Status = "UPCOMING"
			upcomingSeen++
		}
		out = append(out, item)
		if upcomingSeen >= upcomingMeetingCap {
			break
		}
	}
	return out, nil
}

// HandleMeetingRecordDatatable serves the SAME app path for two features, split by the
// meetingNumber query param: with it -> the meeting-summary composite (single meeting);
// without it -> the meeting-calendar records list (all meetings).
func (h *Handler) HandleMeetingRecordDatatable(w http.ResponseWriter, r *http.Request) {
	if strings.TrimSpace(r.URL.Query().Get("meetingNumber")) != "" {
		h.handleMeetingSummary(w, r)
		return
	}
	h.handleMeetingRecordsList(w, r)
}

// handleMeetingRecordsList (meetingcalendar.getMeetingRecords) maps every dt_meeting_record
// row to a MeetingRecordItemDto, wrapped in MeetingRecordListDto.
func (h *Handler) handleMeetingRecordsList(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	centerID, ok := pathInt64Param(w, r, "centerId")
	if !ok {
		return
	}
	records, err := h.readMeetingRecords(centerID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	out := meetingRecordListDto{CenterID: int(centerID), Records: make([]meetingRecordItemDto, 0, len(records))}
	for _, rec := range records {
		out.Records = append(out.Records, meetingRecordItemDto{
			MeetingNumber:   rec.MeetingNumber,
			ActualDate:      fmtFineractDate(rec.MeetingDate),
			TotalSavings:    roundKES(rec.TotalSavingsCollected),
			TotalRepayments: roundKES(rec.TotalRepaymentsReceived),
			AttendanceCount: rec.AttendanceCount,
			ClosingCorpus:   roundKES(rec.ClosingBalance),
		})
	}
	_ = json.NewEncoder(w).Encode(out)
}

// ---- Handlers: meeting-summary ----

// handleMeetingSummary (meetingsummary.getMeetingRecord) resolves ONE completed meeting into
// the composite MeetingSummaryRecordDto: scalar totals from the dt_meeting_record row for
// meetingNumber, plus the per-member savings/loan breakdown from each member's
// dt_meeting_attendance row for that meeting.
func (h *Handler) handleMeetingSummary(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	centerID, ok := pathInt64Param(w, r, "centerId")
	if !ok {
		return
	}
	meetingNumber, err := strconv.Atoi(strings.TrimSpace(r.URL.Query().Get("meetingNumber")))
	if err != nil || meetingNumber <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid meetingNumber")
		return
	}
	records, err := h.readMeetingRecords(centerID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	var rec *fnMeetingRecordRow
	for i := range records {
		if records[i].MeetingNumber == meetingNumber {
			rec = &records[i]
			break
		}
	}
	if rec == nil {
		writeErr(w, http.StatusNotFound, "not_found", "meeting record not found")
		return
	}

	clients, _ := h.resolveCenterClients(centerID)
	savingsBreakdown := make([]savingsBreakdownItemDto, 0, len(clients))
	loanItems := make([]loanSummaryItemDto, 0)
	var groupSavings int64
	for _, c := range clients {
		row := h.attendanceRowForMeeting(c.ID, meetingNumber)
		if row == nil {
			continue
		}
		gs := roundKES(row.SavingsCollected)
		groupSavings += gs
		savingsBreakdown = append(savingsBreakdown, savingsBreakdownItemDto{
			MemberID:          strconv.FormatInt(c.ID, 10),
			MemberName:        c.Name,
			GroupSavings:      gs,
			IndividualSavings: 0,
		})
		if row.RepaymentAmount > 0 {
			loanItems = append(loanItems, loanSummaryItemDto{
				MemberID:     strconv.FormatInt(c.ID, 10),
				MemberName:   c.Name,
				AmountRepaid: roundKES(row.RepaymentAmount),
			})
		}
	}

	// The app records per-member savings as separate savingsaccounts transactions, NOT into the
	// attendance rows, so the per-member breakdown sum (groupSavings) is 0 for app-submitted
	// meetings while the record row carries the real group total. Fall back to the record total so
	// the "Group Savings" card is consistent with "Total Collected" (individual stays 0 — the app's
	// VSLA meetings collect group savings only).
	if groupSavings == 0 {
		groupSavings = roundKES(rec.TotalSavingsCollected)
	}
	_ = json.NewEncoder(w).Encode(meetingSummaryRecordDto{
		MeetingID:                  meetingKey(centerID, meetingNumber),
		MeetingNumber:              meetingNumber,
		ActualDate:                 fmtFineractDate(rec.MeetingDate),
		AttendanceCount:            rec.AttendanceCount,
		TotalMemberCount:           len(clients),
		GroupSavingsCollected:      groupSavings,
		IndividualSavingsCollected: 0,
		TotalSavingsCollected:      roundKES(rec.TotalSavingsCollected),
		LoansDisbursed:             roundKES(rec.TotalLoansDisbursed),
		LoansRepaid:                roundKES(rec.TotalRepaymentsReceived),
		FinesCollected:             roundKES(rec.TotalFinesCollected),
		OpeningCorpus:              roundKES(rec.OpeningBalance),
		ClosingCorpus:              roundKES(rec.ClosingBalance),
		SavingsBreakdown:           savingsBreakdown,
		LoanItems:                  loanItems,
	})
}

// ---- Handlers: previous-meeting-review ----

// HandleMeetingAttendanceList (previousmeetingreview.getMeetingAttendance) returns every
// per-member attendance row for the meeting encoded in meetingId ("{centerId}-{meetingNumber}").
// An empty roster is a valid Content (the app's isEmpty = { false }) -> [], never 404.
func (h *Handler) HandleMeetingAttendanceList(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	centerID, meetingNumber, ok := parseMeetingKey(r.PathValue("meetingId"))
	if !ok {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid meetingId (expected {centerId}-{meetingNumber})")
		return
	}
	clients, err := h.resolveCenterClients(centerID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	out := make([]meetingAttendanceRowDto, 0, len(clients))
	for _, c := range clients {
		row := h.attendanceRowForMeeting(c.ID, meetingNumber)
		if row == nil {
			continue
		}
		out = append(out, meetingAttendanceRowDto{
			MemberID:   strconv.FormatInt(c.ID, 10),
			MemberName: c.Name,
			Status:     row.Status,
			FineAmount: roundKES(row.FineAmount),
		})
	}
	_ = json.NewEncoder(w).Encode(out)
}

// ---- Handlers: meeting-conduct reads ----

// HandlePreviousMeetingRecord (meetingconduct.getPreviousMeetingRecord) returns the LATEST
// meeting's summary row (highest meeting_number). 404 when no meeting has been conducted
// (the app's "first meeting" branch).
func (h *Handler) HandlePreviousMeetingRecord(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	centerID, ok := pathInt64Param(w, r, "centerId")
	if !ok {
		return
	}
	// App forwards groupId as centerId (documented nav-param drift) — map it to the real
	// center id so the previous-meeting record is read from the correct dt_meeting_record rows.
	centerID = h.resolveMeetingCenter(centerID)
	records, err := h.readMeetingRecords(centerID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	if len(records) == 0 {
		writeErr(w, http.StatusNotFound, "not_found", "no previous meeting record")
		return
	}
	latest := records[0]
	for _, rec := range records[1:] {
		if rec.MeetingNumber > latest.MeetingNumber {
			latest = rec
		}
	}
	_ = json.NewEncoder(w).Encode(meetingRecordDetailDto{
		MeetingID:           meetingKey(centerID, latest.MeetingNumber),
		MeetingNumber:       latest.MeetingNumber,
		ActualDate:          fmtFineractDate(latest.MeetingDate),
		TotalSavings:        roundKES(latest.TotalSavingsCollected),
		TotalRepayments:     roundKES(latest.TotalRepaymentsReceived),
		TotalLoansDisbursed: roundKES(latest.TotalLoansDisbursed),
		TotalFinesCollected: roundKES(latest.TotalFinesCollected),
		ClosingCorpus:       roundKES(latest.ClosingBalance),
		AttendanceCount:     latest.AttendanceCount,
	})
}

// HandleCenterDetail (meetingconduct.getGroupMembers) reshapes the center + its groups'
// clients into CenterDetailDto.activeClientMembers.
func (h *Handler) HandleCenterDetail(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	centerID, ok := pathInt64Param(w, r, "centerId")
	if !ok {
		return
	}
	name, err := h.centerName(centerID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	clients, err := h.resolveCenterClients(centerID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	members := make([]clientMemberDto, 0, len(clients))
	for _, c := range clients {
		members = append(members, clientMemberDto{
			ID:               c.ID,
			DisplayName:      c.Name,
			Role:             "Member", // no per-member role linkage on mifos-bank-2; mapper defaults to Member.
			SavingsAccountID: nil,      // savings deposits are not exercised via this facade.
		})
	}
	_ = json.NewEncoder(w).Encode(centerDetailDto{ID: int(centerID), Name: name, ActiveClientMembers: members})
}

// HandleGroupCorpusRead (meetingconduct.getGroupCorpus) returns the single dt_group_corpus
// row. 404 when unset (the app defaults opening corpus / cash-on-hand to 0).
func (h *Handler) HandleGroupCorpusRead(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	centerID, ok := pathInt64Param(w, r, "centerId")
	if !ok {
		return
	}
	// App forwards groupId as centerId (documented nav-param drift) — map it to the real center id.
	centerID = h.resolveMeetingCenter(centerID)
	rows, err := h.readCorpusRows(centerID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	if len(rows) == 0 {
		writeErr(w, http.StatusNotFound, "not_found", "no corpus record")
		return
	}
	c := rows[0]
	_ = json.NewEncoder(w).Encode(corpusRecordDto{
		CenterID:           int(centerID),
		CorpusBalance:      roundKES(c.CorpusBalance),
		CashOnHand:         roundKES(c.CashOnHand),
		LastUpdatedMeeting: c.LastUpdatedMeeting,
		LastUpdatedDate:    fmtFineractDate(c.LastUpdatedDate),
	})
}

// HandleActiveLoans (meetingconduct.getActiveLoans) resolves the group's clients' ACTIVE loans
// into LoanListResponseDto. The app filters by groupId (the group under the center); we walk
// that group's clients' real loan accounts. No loans -> empty (the app's tolerant step 4).
func (h *Handler) HandleActiveLoans(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	groupID, err := strconv.ParseInt(strings.TrimSpace(r.URL.Query().Get("groupId")), 10, 64)
	if err != nil || groupID <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "groupId query param is required")
		return
	}
	clients, err := h.groupClients(groupID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	items := make([]meetingActiveLoanDto, 0)
	for _, c := range clients {
		raw, lerr := h.Fineract.DoRequest("GET", fmt.Sprintf("clients/%d/accounts", c.ID), nil, nil)
		if lerr != nil {
			continue
		}
		var ca fnClientAccounts
		if json.Unmarshal(raw, &ca) != nil {
			continue
		}
		for _, l := range ca.LoanAccounts {
			if !l.Status.Active {
				continue
			}
			item := meetingActiveLoanDto{
				LoanID:             strconv.FormatInt(l.ID, 10),
				ClientID:           strconv.FormatInt(c.ID, 10),
				ClientName:         c.Name,
				OutstandingBalance: roundKES(l.LoanBalance),
				IsOverdue:          l.InArrears,
			}
			// The clients/{id}/accounts summary row carries no principal, installment count, or
			// schedule, so step 4 loan-review would render "KES 0 · Week 0 of 0" for every loan.
			// Enrich from the loan's repayment schedule (one detail fetch per active loan — cheap
			// at meeting scale); on any upstream failure we keep the safe zero-defaults.
			if draw, derr := h.Fineract.DoRequest("GET", fmt.Sprintf("loans/%d", l.ID), nil, map[string]string{"associations": "repaymentSchedule"}); derr == nil {
				var ld fnLoanDetailRaw
				if json.Unmarshal(draw, &ld) == nil {
					item.Principal = roundKES(ld.Principal)
					item.NumberOfRepayments, item.WeekNumber, item.ExpectedWeeklyRepayment =
						summarizeLoanSchedule(ld.RepaymentSchedule.Periods)
				}
			}
			items = append(items, item)
		}
	}
	_ = json.NewEncoder(w).Encode(loanListResponseDto{TotalFilteredRecords: len(items), PageItems: items})
}

// summarizeLoanSchedule derives the meeting-review projection from a loan's repayment schedule:
//   - numberOfRepayments: real installment periods only (Fineract emits a period==0/null
//     disbursement row that is NOT an installment).
//   - weekNumber: the installment the group is collecting for this meeting — the first
//     not-yet-complete period's number; if every installment is paid it pins to the last (a
//     fully-paid loan would not be ACTIVE, so this only guards the edge).
//   - expectedWeekly: the current week's total due (what the treasurer collects now), falling
//     back to the first payable installment for the fully-paid guard. Flat weekly VSLA loans
//     have a uniform installment, so the two coincide in the common case.
func summarizeLoanSchedule(periods []fnLoanPeriod) (numberOfRepayments, weekNumber int, expectedWeekly int64) {
	var currentDue, firstDue float64
	for _, p := range periods {
		if p.Period == nil || *p.Period <= 0 {
			continue // skip the disbursement row
		}
		numberOfRepayments++
		if firstDue == 0 && p.TotalDueForPeriod > 0 {
			firstDue = p.TotalDueForPeriod
		}
		if !p.Complete && weekNumber == 0 {
			weekNumber = *p.Period
			currentDue = p.TotalDueForPeriod
		}
	}
	if weekNumber == 0 {
		weekNumber = numberOfRepayments // all installments complete → last week
	}
	if currentDue == 0 {
		currentDue = firstDue
	}
	return numberOfRepayments, weekNumber, roundKES(currentDue)
}

// HandleLoanVotes (meetingconduct.getLoanVotes) returns the single dt_loan_vote tally for a
// loan. "No votes cast yet" is a NORMAL state, NOT an error: a loan simply hasn't been voted on
// until a meeting records votes. The app's getLoanVotes uses the generic requestAsNetworkResult
// wrapper (no 404 branch), so a 404 would surface a spurious NetworkResult.Error mid
// meeting-conduct. LoanVoteRecordDto defaults every field to 0, so we return a 200 ZERO-TALLY
// for the empty case — unambiguous "0 votes so far", never a soft-fail 404.
func (h *Handler) HandleLoanVotes(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	loanID := strings.TrimSpace(r.PathValue("loanId"))
	if loanID == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid loanId")
		return
	}
	zeroTally := loanVoteRecordDto{LoanID: loanID} // VotesFor/Against/Abstain default to 0
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("datatables/%s/%s", loanVoteTable, loanID), nil, nil)
	if err != nil {
		// datatable empty / no row for this loan (Fineract 404) — a zero tally, not an error.
		_ = json.NewEncoder(w).Encode(zeroTally)
		return
	}
	var rows []fnLoanVoteRow
	if json.Unmarshal(raw, &rows) != nil || len(rows) == 0 {
		_ = json.NewEncoder(w).Encode(zeroTally)
		return
	}
	v := rows[0]
	_ = json.NewEncoder(w).Encode(loanVoteRecordDto{
		LoanID:       loanID,
		VotesFor:     v.VotesFor,
		VotesAgainst: v.VotesAgainst,
		VotesAbstain: v.VotesAbstain,
	})
}

// ---- Handlers: meeting-conduct writes ----

// HandlePostMeetingRecord (meetingconduct.postMeetingRecord, submit priority 1) writes the
// conducted-meeting summary row to dt_meeting_record/{centerId} and returns its row id.
func (h *Handler) HandlePostMeetingRecord(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	var in createMeetingRecordRequestIn
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	if in.CenterID <= 0 {
		in.CenterID = in.GroupID // app sends the scope as groupId (Group-centric)
	}
	if in.CenterID <= 0 || in.MeetingNumber <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "groupId (or centerId) and meetingNumber are required")
		return
	}
	row := map[string]interface{}{
		"meeting_number":            in.MeetingNumber,
		"meeting_date":              normalizeFineractDate(in.ActualDate),
		"opening_balance":           in.OpeningCorpus,
		"closing_balance":           in.ClosingCorpus,
		"total_savings_collected":   in.TotalSavingsCollected,
		"total_repayments_received": in.TotalRepaymentsReceived,
		"total_loans_disbursed":     in.TotalLoansDisbursed,
		"total_fines_collected":     in.TotalFinesCollected,
		"total_inflows":             in.TotalSavingsCollected + in.TotalRepaymentsReceived,
		"total_outflows":            in.TotalLoansDisbursed,
		"attendance_count":          in.AttendanceCount,
		"dateFormat":                "yyyy-MM-dd",
		"locale":                    "en",
	}
	resourceID, err := h.writeDatatableRow(meetingRecordTable, in.i64(), row)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "write meeting record: "+err.Error())
		return
	}
	_ = json.NewEncoder(w).Encode(dataTableEntryResponseDto{ResourceID: resourceID})
}

// HandlePostMeetingAttendance (meetingconduct.postMeetingAttendance, submit priority 2) writes
// one per-member attendance row to dt_meeting_attendance/{memberId} (apptable m_client). The
// meeting is keyed by meeting_number, parsed from the request's opaque meetingId.
func (h *Handler) HandlePostMeetingAttendance(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	var in createAttendanceRequestIn
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	memberID, err := strconv.ParseInt(strings.TrimSpace(in.MemberID), 10, 64)
	if err != nil || memberID <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "memberId is required")
		return
	}
	_, meetingNumber, ok := parseMeetingKey(in.MeetingID)
	if !ok {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid meetingId (expected {centerId}-{meetingNumber})")
		return
	}
	status := strings.TrimSpace(in.Status)
	if status == "" {
		status = "ABSENT"
	}
	row := map[string]interface{}{
		"meeting_number": meetingNumber,
		"status":         status,
		"fine_amount":    in.FineAmount,
		"locale":         "en",
	}
	if in.SavingsCollected != nil {
		row["savings_collected"] = *in.SavingsCollected
	}
	if in.RepaymentAmount != nil {
		row["repayment_amount"] = *in.RepaymentAmount
	}
	resourceID, err := h.writeDatatableRow(meetingAttendanceTable, memberID, row)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "write attendance: "+err.Error())
		return
	}
	_ = json.NewEncoder(w).Encode(dataTableEntryResponseDto{ResourceID: resourceID})
}

// HandlePostSavingsTransaction (meetingconduct.postSavingsTransaction, submit priority 3) posts a
// real savings DEPOSIT against {savingsId}. transactionDate is normalized to Fineract's date-only
// "dd MMMM yyyy" via the shared fineractTxnDate (share_out_write.go).
func (h *Handler) HandlePostSavingsTransaction(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	savingsID := strings.TrimSpace(r.PathValue("savingsId"))
	if savingsID == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid savingsId")
		return
	}
	var in savingsTransactionRequestIn
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	command := strings.TrimSpace(r.URL.Query().Get("command"))
	if command == "" {
		command = "deposit"
	}
	body := map[string]interface{}{
		"transactionDate":   fineractTxnDate(in.TransactionDate),
		"transactionAmount": in.TransactionAmount,
		"dateFormat":        "dd MMMM yyyy",
		"locale":            "en",
	}
	if in.PaymentTypeID > 0 {
		body["paymentTypeId"] = in.PaymentTypeID
	}
	raw, err := h.Fineract.DoRequest("POST", fmt.Sprintf("savingsaccounts/%s/transactions", savingsID), body, map[string]string{"command": command})
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", strings.TrimSpace(string(nonEmpty(raw))))
		return
	}
	var resp struct {
		ResourceID int64 `json:"resourceId"`
	}
	_ = json.Unmarshal(raw, &resp)
	_ = json.NewEncoder(w).Encode(dataTableEntryResponseDto{ResourceID: resp.ResourceID})
}

// HandleUpdateCorpus (meetingconduct.updateCorpus, submit priority 6) upserts the single
// dt_group_corpus row for the center (POST to create, PUT to update) with the closing corpus.
func (h *Handler) HandleUpdateCorpus(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	centerID, ok := pathInt64Param(w, r, "centerId")
	if !ok {
		return
	}
	var in updateCorpusRequestIn
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	row := map[string]interface{}{
		"corpus_balance":       in.CorpusBalance,
		"closing_balance":      in.CorpusBalance,
		"last_updated_meeting": in.LastUpdatedMeeting,
		"meeting_number":       in.LastUpdatedMeeting,
		"last_updated_date":    normalizeFineractDate(in.LastUpdatedDate),
		"dateFormat":           "yyyy-MM-dd",
		"locale":               "en",
	}
	existing, _ := h.readCorpusRows(centerID)
	method := "POST"
	if len(existing) > 0 {
		method = "PUT" // single-row datatable: PUT updates the existing row.
	}
	raw, err := h.Fineract.DoRequest(method, fmt.Sprintf("datatables/%s/%d", groupCorpusTable, centerID), row, nil)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "update corpus: "+strings.TrimSpace(string(nonEmpty(raw))))
		return
	}
	var resp struct {
		ResourceID int64 `json:"resourceId"`
	}
	_ = json.Unmarshal(raw, &resp)
	if resp.ResourceID == 0 {
		resp.ResourceID = centerID // PUT echoes the apptable id.
	}
	_ = json.NewEncoder(w).Encode(dataTableEntryResponseDto{ResourceID: resp.ResourceID})
}

// HandleRescheduleCalendar (meeting-calendar G3/F6 RescheduleMeeting) forwards a calendar update
// to Fineract's real PUT /centers/{id}/calendars/{cid}?command=updateCalendar. The app currently
// offline-queues this (server-gated — see RescheduleMeetingRequest KDoc), so the precise
// RescheduleMeetingRequest->Fineract field mapping is resolved server-side when the live write
// lands; this passthrough forwards the received body verbatim, injecting dateFormat/locale so a
// startDate change parses.
func (h *Handler) HandleRescheduleCalendar(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	centerID, ok := pathInt64Param(w, r, "centerId")
	if !ok {
		return
	}
	calendarID := strings.TrimSpace(r.PathValue("calendarId"))
	if calendarID == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid calendarId")
		return
	}
	body := map[string]interface{}{}
	if r.Body != nil {
		_ = json.NewDecoder(r.Body).Decode(&body)
	}
	if _, has := body["dateFormat"]; !has {
		body["dateFormat"] = "yyyy-MM-dd"
	}
	if _, has := body["locale"]; !has {
		body["locale"] = "en"
	}
	command := strings.TrimSpace(r.URL.Query().Get("command"))
	if command == "" {
		command = "updateCalendar"
	}
	raw, err := h.Fineract.DoRequest("PUT", fmt.Sprintf("centers/%d/calendars/%s", centerID, calendarID), body, map[string]string{"command": command})
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", strings.TrimSpace(string(nonEmpty(raw))))
		return
	}
	_, _ = w.Write(raw)
}

// ---- Fineract reads (service credential via DoRequest) ----

// centerMeetingDates returns the scheduled meeting dates ([y,m,d] each) from the center's
// COLLECTION calendar recurrence (falls back to the first calendar if none is typed COLLECTION).
func (h *Handler) centerMeetingDates(centerID int64) ([][]int, error) {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("centers/%d/calendars", centerID), nil, nil)
	if err != nil {
		// No center / no calendar endpoint (a companion-created VSLA group has no parent center) —
		// synthesize a default weekly schedule so the group still has conductable meetings.
		return synthesizeWeeklyMeetingDates(), nil
	}
	var cals []fnCalendar
	if err := json.Unmarshal(raw, &cals); err != nil {
		return synthesizeWeeklyMeetingDates(), nil
	}
	var chosen *fnCalendar
	for i := range cals {
		if strings.EqualFold(cals[i].Type.Value, "COLLECTION") {
			chosen = &cals[i]
			break
		}
	}
	if chosen == nil && len(cals) > 0 {
		chosen = &cals[0]
	}
	// No calendar attached OR the calendar carries no recurring dates → fall back to the
	// synthesized weekly schedule (VSLA groups created via the companion have neither).
	if chosen == nil || len(chosen.RecurringDates) == 0 {
		return synthesizeWeeklyMeetingDates(), nil
	}
	return chosen.RecurringDates, nil
}

// synthesizeWeeklyMeetingDates builds a default WEEKLY VSLA meeting schedule for groups that have
// no Fineract meeting calendar attached (the companion's create-active-in-one-call VSLA groups).
// It returns 8 weekly [year, month, day] dates spanning two weeks back (which render as MISSED or,
// once conducted, COMPLETED) through six weeks ahead (UPCOMING) — so every active group always has
// a conductable UPCOMING meeting on its calendar. Matches the WEEKLY cadence the group-dashboard
// + organizer-dashboard already advertise.
func synthesizeWeeklyMeetingDates() [][]int {
	start := time.Now().AddDate(0, 0, -14) // two weeks ago → a couple of past slots + several upcoming
	out := make([][]int, 0, 8)
	for i := 0; i < 8; i++ {
		d := start.AddDate(0, 0, i*7)
		out = append(out, []int{d.Year(), int(d.Month()), d.Day()})
	}
	return out
}

// readMeetingRecords reads every dt_meeting_record row for the center. An empty/unprovisioned
// datatable yields an empty slice (never an error) so callers degrade gracefully.
func (h *Handler) readMeetingRecords(centerID int64) ([]fnMeetingRecordRow, error) {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("datatables/%s/%d", meetingRecordTable, centerID), nil, nil)
	if err != nil {
		return []fnMeetingRecordRow{}, nil
	}
	var rows []fnMeetingRecordRow
	if err := json.Unmarshal(raw, &rows); err != nil {
		return []fnMeetingRecordRow{}, nil
	}
	return rows, nil
}

// readCorpusRows reads the (0- or 1-element) dt_group_corpus array for the center.
func (h *Handler) readCorpusRows(centerID int64) ([]fnCorpusRow, error) {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("datatables/%s/%d", groupCorpusTable, centerID), nil, nil)
	if err != nil {
		return []fnCorpusRow{}, nil
	}
	var rows []fnCorpusRow
	if err := json.Unmarshal(raw, &rows); err != nil {
		return []fnCorpusRow{}, nil
	}
	return rows, nil
}

// attendanceRowForMeeting returns the client's dt_meeting_attendance row for meetingNumber, or nil.
func (h *Handler) attendanceRowForMeeting(clientID int64, meetingNumber int) *fnAttendanceRow {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("datatables/%s/%d", meetingAttendanceTable, clientID), nil, nil)
	if err != nil {
		return nil
	}
	var rows []fnAttendanceRow
	if json.Unmarshal(raw, &rows) != nil {
		return nil
	}
	for i := range rows {
		if rows[i].MeetingNumber == meetingNumber {
			return &rows[i]
		}
	}
	return nil
}

// centerName reads the center's display name.
func (h *Handler) centerName(centerID int64) (string, error) {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("centers/%d", centerID), nil, nil)
	if err != nil {
		return "", fmt.Errorf("read center: %s", strings.TrimSpace(string(nonEmpty(raw))))
	}
	var c struct {
		Name string `json:"name"`
	}
	_ = json.Unmarshal(raw, &c)
	return c.Name, nil
}

// resolveCenterClients flattens the center -> associated groups -> client members into a
// deduped list. This is the real Fineract member linkage (a Fineract center contains groups,
// groups contain clients — there is no direct center<->client membership).
func (h *Handler) resolveCenterClients(centerID int64) ([]centerClient, error) {
	seen := make(map[int64]bool)
	out := make([]centerClient, 0)
	// Primary path: walk the center's group members → each group's clients.
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("centers/%d", centerID), nil, map[string]string{"associations": "groupMembers"})
	if err == nil {
		var c struct {
			GroupMembers []struct {
				ID int64 `json:"id"`
			} `json:"groupMembers"`
		}
		if jerr := json.Unmarshal(raw, &c); jerr == nil {
			for _, g := range c.GroupMembers {
				clients, gerr := h.groupClients(g.ID)
				if gerr != nil {
					continue
				}
				for _, m := range clients {
					if !seen[m.ID] {
						seen[m.ID] = true
						out = append(out, m)
					}
				}
			}
		}
	}
	// Fallback: the app forwards a groupId where a centerId is expected (documented nav-param
	// drift — group-dashboard has no centerId, so it keys meeting-calendar/-conduct by groupId,
	// and the meeting endpoints already tolerate that). When the center walk yields nothing,
	// resolve the id AS a group directly so the attendance roster is never empty for a real group.
	if len(out) == 0 {
		if clients, gerr := h.groupClients(centerID); gerr == nil {
			for _, m := range clients {
				if !seen[m.ID] {
					seen[m.ID] = true
					out = append(out, m)
				}
			}
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}

// groupClients returns a group's active client members.
func (h *Handler) groupClients(groupID int64) ([]centerClient, error) {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("groups/%d", groupID), nil, map[string]string{"associations": "clientMembers"})
	if err != nil {
		return nil, fmt.Errorf("read group %d: %s", groupID, strings.TrimSpace(string(nonEmpty(raw))))
	}
	var g struct {
		ClientMembers []struct {
			ID          int64  `json:"id"`
			DisplayName string `json:"displayName"`
		} `json:"clientMembers"`
	}
	if err := json.Unmarshal(raw, &g); err != nil {
		return nil, fmt.Errorf("decode group %d: %w", groupID, err)
	}
	out := make([]centerClient, 0, len(g.ClientMembers))
	for _, m := range g.ClientMembers {
		out = append(out, centerClient{ID: m.ID, Name: m.DisplayName})
	}
	return out, nil
}

// centerClient is a flattened center member (client id + display name).
type centerClient struct {
	ID   int64
	Name string
}

// ---- helpers ----

// i64 exposes createMeetingRecordRequestIn.CenterID (int) as int64 at the datatable-write call
// site without a scattered cast.
func (in createMeetingRecordRequestIn) i64() int64 { return int64(in.CenterID) }

// pathInt64Param reads a positive int64 path variable, writing a 400 on failure.
func pathInt64Param(w http.ResponseWriter, r *http.Request, name string) (int64, bool) {
	id, err := strconv.ParseInt(strings.TrimSpace(r.PathValue(name)), 10, 64)
	if err != nil || id <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid "+name)
		return 0, false
	}
	return id, true
}

// meetingKey encodes the opaque meetingId this facade owns end-to-end.
func meetingKey(centerID int64, meetingNumber int) string {
	return fmt.Sprintf("%d-%d", centerID, meetingNumber)
}

// parseMeetingKey decodes "{centerId}-{meetingNumber}" back into its parts.
func parseMeetingKey(s string) (centerID int64, meetingNumber int, ok bool) {
	parts := strings.SplitN(strings.TrimSpace(s), "-", 2)
	if len(parts) != 2 {
		return 0, 0, false
	}
	c, err1 := strconv.ParseInt(parts[0], 10, 64)
	m, err2 := strconv.Atoi(parts[1])
	if err1 != nil || err2 != nil || c <= 0 || m <= 0 {
		return 0, 0, false
	}
	return c, m, true
}

// roundKES converts a Fineract decimal (e.g. 2500.000000) to the whole-KES Long the app DTOs use.
func roundKES(f float64) int64 { return int64(math.Round(f)) }

// todayYMD returns the host's current [y,m,d].
func todayYMD() []int {
	now := time.Now()
	return []int{now.Year(), int(now.Month()), now.Day()}
}

// ymdBefore reports whether the [y,m,d] date a is strictly before b.
func ymdBefore(a, b []int) bool {
	if len(a) < 3 || len(b) < 3 {
		return false
	}
	for i := 0; i < 3; i++ {
		if a[i] != b[i] {
			return a[i] < b[i]
		}
	}
	return false
}

// normalizeFineractDate coerces any inbound date string to canonical "yyyy-MM-dd" for a Fineract
// DATE-column write. Accepts a bare ISO date, a full ISO instant, "dd MMMM yyyy", or empty
// (-> host today). See the file header DATE-FORMAT decisions for why the app's own dateFormat
// field is not trusted.
func normalizeFineractDate(s string) string {
	const out = "2006-01-02"
	s = strings.TrimSpace(s)
	if s == "" {
		return time.Now().Format(out)
	}
	if len(s) >= 10 {
		if t, err := time.Parse(out, s[:10]); err == nil {
			return t.Format(out)
		}
	}
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return t.Format(out)
	}
	if t, err := time.Parse("02 January 2006", s); err == nil {
		return t.Format(out)
	}
	return time.Now().Format(out)
}
