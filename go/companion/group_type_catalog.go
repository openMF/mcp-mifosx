// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package companion

// Group-type-config CATALOGUE endpoint. The MifosSave group-type-picker calls
// GET /companion/datatables/group_type_config/{entityId} and expects a
// List<GroupTypeConfigDto> (core/network/.../model/GroupTypeConfigDto.kt). This is
// the app's own VSLA-archetype taxonomy (9 types) — a static catalogue, not
// Fineract data. Display names/taglines match the app's canonical fixtures
// (GroupTypeConfigRepositoryTest.kt). entityId is ignored (catalogue is global).

import (
	"encoding/json"
	"net/http"
	"strings"
)

// catalogBySlug returns the archetype catalogue entry for a slug (e.g. "VSLA") and
// whether it was found. group-create uses it to fill the dt_group_type_config
// mandatory display_name + archetype-default columns (lending/welfare/size) from the
// chosen group type. Case- and whitespace-insensitive on the slug.
func catalogBySlug(slug string) (GroupTypeConfigDto, bool) {
	s := strings.ToUpper(strings.TrimSpace(slug))
	for _, c := range groupTypeCatalog {
		if strings.ToUpper(c.TypeSlug) == s {
			return c, true
		}
	}
	return GroupTypeConfigDto{}, false
}

// titleFromSlug is the display_name fallback when a slug is not in the catalogue:
// "CBO_VILLAGE_BANK" -> "Cbo Village Bank". Only reached for a non-catalogue slug.
func titleFromSlug(slug string) string {
	parts := strings.FieldsFunc(slug, func(r rune) bool { return r == '_' || r == '-' })
	for i, p := range parts {
		if p == "" {
			continue
		}
		parts[i] = strings.ToUpper(p[:1]) + strings.ToLower(p[1:])
	}
	return strings.Join(parts, " ")
}

// GroupTypeConfigDto mirrors the app DTO field-for-field (camelCase @SerialName).
type GroupTypeConfigDto struct {
	TypeSlug                 string  `json:"typeSlug"`
	DisplayName              string  `json:"displayName"`
	Tagline                  string  `json:"tagline"`
	SavingsMechanism         string  `json:"savingsMechanism"`
	ContributionMode         string  `json:"contributionMode"`
	LendingEnabled           bool    `json:"lendingEnabled"`
	HasSocialFund            bool    `json:"hasSocialFund"`
	HasBankLinkage           bool    `json:"hasBankLinkage"`
	WelfareOnlyMode          bool    `json:"welfareOnlyMode"`
	FormallyRegistered       bool    `json:"formallyRegistered"`
	DefaultLoanMultiplier    float64 `json:"defaultLoanMultiplier"`
	DefaultInterestRatePct   float64 `json:"defaultInterestRatePct"`
	DefaultCycleLengthMonths int     `json:"defaultCycleLengthMonths"`
	MaxMembers               int     `json:"maxMembers"`
	MinMembers               int     `json:"minMembers"`
}

func (h *Handler) registerGroupTypeCatalogRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /companion/datatables/group_type_config/{entityId}", h.HandleGroupTypeCatalog)
	// bare path (entityId omitted) → same catalogue
	mux.HandleFunc("GET /companion/datatables/group_type_config", h.HandleGroupTypeCatalog)
}

// HandleGroupTypeCatalog returns the 9-archetype VSLA group-type catalogue. Values
// mirror the app's canonical GroupTypeConfigRepositoryTest fixtures + sensible
// archetype defaults; the picker prefills the create-group wizard rules step from
// the chosen type.
func (h *Handler) HandleGroupTypeCatalog(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	_ = json.NewEncoder(w).Encode(groupTypeCatalog)
}

var groupTypeCatalog = []GroupTypeConfigDto{
	{
		TypeSlug: "VSLA", DisplayName: "Village Savings & Loan Association",
		Tagline: "Save in shares; borrow up to 3x; share-out at year end",
		SavingsMechanism: "ACCUMULATING", ContributionMode: "SHARE_BASED_VARIABLE",
		LendingEnabled: true, HasSocialFund: true, HasBankLinkage: false,
		WelfareOnlyMode: false, FormallyRegistered: false,
		DefaultLoanMultiplier: 3, DefaultInterestRatePct: 10, DefaultCycleLengthMonths: 12,
		MaxMembers: 30, MinMembers: 5,
	},
	{
		TypeSlug: "ROSCA", DisplayName: "Rotating Savings & Credit Association",
		Tagline: "Everyone contributes; one member takes the pot each cycle",
		SavingsMechanism: "ROTATING_PAYOUT", ContributionMode: "FIXED_AMOUNT",
		LendingEnabled: false, HasSocialFund: false, HasBankLinkage: false,
		WelfareOnlyMode: false, FormallyRegistered: false,
		DefaultLoanMultiplier: 0, DefaultInterestRatePct: 0, DefaultCycleLengthMonths: 12,
		MaxMembers: 20, MinMembers: 3,
	},
	{
		TypeSlug: "ASCA", DisplayName: "Accumulating Savings & Credit Association",
		Tagline: "Pooled savings with interest-bearing loans, no year-end share-out",
		SavingsMechanism: "ACCUMULATING", ContributionMode: "FIXED_AMOUNT",
		LendingEnabled: true, HasSocialFund: false, HasBankLinkage: false,
		WelfareOnlyMode: false, FormallyRegistered: false,
		DefaultLoanMultiplier: 2, DefaultInterestRatePct: 8, DefaultCycleLengthMonths: 0,
		MaxMembers: 40, MinMembers: 5,
	},
	{
		TypeSlug: "SILC", DisplayName: "Savings & Internal Lending Community",
		Tagline: "CRS-model self-managed savings & lending group",
		SavingsMechanism: "ACCUMULATING", ContributionMode: "SHARE_BASED_VARIABLE",
		LendingEnabled: true, HasSocialFund: true, HasBankLinkage: false,
		WelfareOnlyMode: false, FormallyRegistered: false,
		DefaultLoanMultiplier: 3, DefaultInterestRatePct: 10, DefaultCycleLengthMonths: 12,
		MaxMembers: 25, MinMembers: 5,
	},
	{
		TypeSlug: "SHG", DisplayName: "Self-Help Group",
		Tagline: "10-20 members; bank-linked group lending (India SHG model)",
		SavingsMechanism: "ACCUMULATING", ContributionMode: "FIXED_AMOUNT",
		LendingEnabled: true, HasSocialFund: false, HasBankLinkage: true,
		WelfareOnlyMode: false, FormallyRegistered: true,
		DefaultLoanMultiplier: 4, DefaultInterestRatePct: 12, DefaultCycleLengthMonths: 0,
		MaxMembers: 20, MinMembers: 10,
	},
	{
		TypeSlug: "SACCO", DisplayName: "Savings & Credit Cooperative",
		Tagline: "Registered cooperative; shares, deposits & member loans",
		SavingsMechanism: "ACCUMULATING", ContributionMode: "SHARE_BASED_VARIABLE",
		LendingEnabled: true, HasSocialFund: false, HasBankLinkage: true,
		WelfareOnlyMode: false, FormallyRegistered: true,
		DefaultLoanMultiplier: 3, DefaultInterestRatePct: 12, DefaultCycleLengthMonths: 0,
		MaxMembers: 200, MinMembers: 10,
	},
	{
		TypeSlug: "CBO_VILLAGE_BANK", DisplayName: "Village Bank (CBO)",
		Tagline: "Community-based village banking with an external seed loan",
		SavingsMechanism: "ACCUMULATING", ContributionMode: "FIXED_AMOUNT",
		LendingEnabled: true, HasSocialFund: true, HasBankLinkage: true,
		WelfareOnlyMode: false, FormallyRegistered: true,
		DefaultLoanMultiplier: 3, DefaultInterestRatePct: 10, DefaultCycleLengthMonths: 12,
		MaxMembers: 50, MinMembers: 10,
	},
	{
		TypeSlug: "BURIAL_WELFARE", DisplayName: "Burial / Welfare Society",
		Tagline: "Members contribute to a welfare fund for emergencies & funerals",
		SavingsMechanism: "ACCUMULATING", ContributionMode: "FIXED_AMOUNT",
		LendingEnabled: false, HasSocialFund: true, HasBankLinkage: false,
		WelfareOnlyMode: true, FormallyRegistered: false,
		DefaultLoanMultiplier: 0, DefaultInterestRatePct: 0, DefaultCycleLengthMonths: 0,
		MaxMembers: 100, MinMembers: 5,
	},
	{
		TypeSlug: "JLG", DisplayName: "Joint Liability Group",
		Tagline: "4-10 members take an external MFI loan jointly",
		SavingsMechanism: "NONE", ContributionMode: "FIXED_AMOUNT",
		LendingEnabled: true, HasSocialFund: false, HasBankLinkage: true,
		WelfareOnlyMode: false, FormallyRegistered: true,
		DefaultLoanMultiplier: 1, DefaultInterestRatePct: 15, DefaultCycleLengthMonths: 0,
		MaxMembers: 10, MinMembers: 4,
	},
}
