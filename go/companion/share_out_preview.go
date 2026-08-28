// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package companion

// Share-out PREVIEW read/compute endpoint. The MifosSave share-out-preview
// screen calls GET /companion/groups/{groupId}/shareout/preview and expects a
// ShareOutPreviewDto (core/network/.../model/ShareOutDto.kt) — the strategy-aware
// distribution preview BEFORE the (destructive) execute. Non-destructive: reads
// the real group corpus + members from Fineract and computes each member's share.

import (
	"encoding/json"
	"net/http"
	"strconv"
)

// MemberPayoutDto mirrors the app DTO.
type MemberPayoutDto struct {
	MemberID     string   `json:"memberId"`
	MemberName   string   `json:"memberName"`
	SharesHeld   *int     `json:"sharesHeld,omitempty"`
	TotalSavings *float64 `json:"totalSavings,omitempty"`
	SharePercent float64  `json:"sharePercent"`
	PayoutAmount float64  `json:"payoutAmount"`
}

// ShareOutPreviewDto mirrors the app DTO field-for-field.
type ShareOutPreviewDto struct {
	CycleNumber         int               `json:"cycleNumber"`
	PoolModel           string            `json:"poolModel"`
	ShareoutFormula     string            `json:"shareoutFormula"`
	TotalCorpus         float64           `json:"totalCorpus"`
	TotalProfit         float64           `json:"totalProfit"`
	TotalPool           float64           `json:"totalPool"`
	MemberPayouts       []MemberPayoutDto `json:"memberPayouts"`
	RotationPosition    *int              `json:"rotationPosition,omitempty"`
	NextRecipientName   *string           `json:"nextRecipientName,omitempty"`
	NextRecipientAmount *float64          `json:"nextRecipientAmount,omitempty"`
}

func (h *Handler) registerShareOutPreviewRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /companion/groups/{groupId}/shareout/preview", h.HandleShareOutPreview)
}

// HandleShareOutPreview computes the year-end share-out distribution from the real
// group corpus (group savings balance) split pro-rata across active members. For an
// ACCUMULATING VSLA pool with no per-member share ledger on Fineract, the corpus is
// split equally (each member's sharePercent = payout/pool); totalProfit defaults to
// 0 (no separate interest ledger surfaced). Non-destructive — no writes.
func (h *Handler) HandleShareOutPreview(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	groupID, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	detail, members, err := h.aggregateGroup(groupID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	corpus, _, _ := h.aggregateSavings(groupID)
	pool := corpus // totalProfit 0 → pool == corpus

	payouts := make([]MemberPayoutDto, 0, len(members))
	n := len(members)
	if n > 0 && pool > 0 {
		per := pool / float64(n)
		pct := 100.0 / float64(n)
		for _, m := range members {
			amt := per
			p := pct
			payouts = append(payouts, MemberPayoutDto{
				MemberID:     strconv.FormatInt(m.ID, 10),
				MemberName:   m.Name,
				SharePercent: round2(p),
				PayoutAmount: round2(amt),
			})
		}
	} else {
		for _, m := range members {
			payouts = append(payouts, MemberPayoutDto{
				MemberID: strconv.FormatInt(m.ID, 10), MemberName: m.Name,
				SharePercent: 0, PayoutAmount: 0,
			})
		}
	}

	_ = json.NewEncoder(w).Encode(ShareOutPreviewDto{
		CycleNumber:     detail.CycleNumber,
		PoolModel:       "ACCUMULATING",
		ShareoutFormula: "PRO_RATA_BY_SHARES",
		TotalCorpus:     round2(corpus),
		TotalProfit:     0,
		TotalPool:       round2(pool),
		MemberPayouts:   payouts,
	})
}

func round2(v float64) float64 {
	return float64(int64(v*100+0.5)) / 100
}
