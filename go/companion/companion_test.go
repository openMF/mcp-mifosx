// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package companion

import "testing"

func TestDecodeSessionToken(t *testing.T) {
	// base64("mifos:password") == "bWlmb3M6cGFzc3dvcmQ=" (the exact Fineract key)
	user, pass, ok := decodeSessionToken("bWlmb3M6cGFzc3dvcmQ=")
	if !ok || user != "mifos" || pass != "password" {
		t.Fatalf("got (%q,%q,%v), want (mifos,password,true)", user, pass, ok)
	}
	if _, _, ok := decodeSessionToken("!!!not-base64!!!"); ok {
		t.Fatalf("expected malformed token to be rejected")
	}
	if _, _, ok := decodeSessionToken(""); ok {
		t.Fatalf("expected empty token to be rejected")
	}
}

func TestSplitName(t *testing.T) {
	cases := []struct {
		in, first, last string
	}{
		{"Ada Lovelace", "Ada", "Lovelace"},
		{"Cher", "Cher", "Cher"},
		{"Jean Luc Picard", "Jean", "Luc Picard"},
		{"  ", "", ""},
	}
	for _, c := range cases {
		f, l := splitName(c.in)
		if f != c.first || l != c.last {
			t.Errorf("splitName(%q) = (%q,%q), want (%q,%q)", c.in, f, l, c.first, c.last)
		}
	}
}
