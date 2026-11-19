// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package idempotency

import (
	"encoding/json"
	"math"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type vectors struct {
	Version      int               `json:"version"`
	Scope        string            `json:"scope"`
	ScopeEnv     map[string]string `json:"scope_env"`
	Fingerprints []struct {
		Name        string         `json:"name"`
		Operation   string         `json:"operation"`
		Payload     map[string]any `json:"payload"`
		Canonical   string         `json:"canonical"`
		Fingerprint string         `json:"fingerprint"`
	} `json:"fingerprints"`
	Keys []struct {
		Name       string `json:"name"`
		Scope      string `json:"scope"`
		Operation  string `json:"operation"`
		Key        string `json:"key"`
		KeyHash    string `json:"key_hash"`
		BackendKey string `json:"backend_key"`
	} `json:"keys"`
	Records []recordVector `json:"records"`
}

type recordVector struct {
	Name      string         `json:"name"`
	Scope     string         `json:"scope"`
	Operation string         `json:"operation"`
	Key       string         `json:"key"`
	Payload   map[string]any `json:"payload"`
	Record    Record         `json:"record"`
}

// loadVectors reads the fixture shared with python/tests/test_idempotency.py.
func loadVectors(t *testing.T) vectors {
	t.Helper()
	path := filepath.Join("testdata", "idempotency_vectors.json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read shared vectors: %v", err)
	}
	var fixture vectors
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatalf("parse shared vectors: %v", err)
	}
	if fixture.Version != LedgerVersion {
		t.Fatalf("vector version %d does not match ledger version %d", fixture.Version, LedgerVersion)
	}
	return fixture
}

func TestFingerprintMatchesPythonVectors(t *testing.T) {
	fixture := loadVectors(t)
	if len(fixture.Fingerprints) == 0 {
		t.Fatal("no fingerprint vectors")
	}
	for _, vector := range fixture.Fingerprints {
		vector := vector
		t.Run(vector.Name, func(t *testing.T) {
			material, err := Canonical(map[string]any{
				"operation": vector.Operation,
				"payload":   vector.Payload,
			})
			if err != nil {
				t.Fatalf("canonical: %v", err)
			}
			if material != vector.Canonical {
				t.Fatalf("canonical mismatch\n got: %s\nwant: %s", material, vector.Canonical)
			}
			fingerprint, err := Fingerprint(vector.Operation, vector.Payload)
			if err != nil {
				t.Fatalf("fingerprint: %v", err)
			}
			if fingerprint != vector.Fingerprint {
				t.Fatalf("fingerprint mismatch: got %s want %s", fingerprint, vector.Fingerprint)
			}
		})
	}
}

func TestKeyHashesMatchPythonVectors(t *testing.T) {
	fixture := loadVectors(t)
	for _, vector := range fixture.Keys {
		vector := vector
		t.Run(vector.Name, func(t *testing.T) {
			if got := KeyHash(vector.Scope, vector.Operation, vector.Key); got != vector.KeyHash {
				t.Fatalf("key hash mismatch: got %s want %s", got, vector.KeyHash)
			}
			if got := BackendKey(vector.Scope, vector.Operation, vector.Key); got != vector.BackendKey {
				t.Fatalf("backend key mismatch: got %s want %s", got, vector.BackendKey)
			}
			if strings.Contains(vector.BackendKey, vector.Key) && len(vector.Key) > 8 {
				t.Fatalf("backend key leaks the client key: %s", vector.BackendKey)
			}
		})
	}
}

func TestDefaultScopeMatchesPythonVector(t *testing.T) {
	fixture := loadVectors(t)
	for name, value := range fixture.ScopeEnv {
		t.Setenv(name, value)
	}
	if got := DefaultScope(); got != fixture.Scope {
		t.Fatalf("scope mismatch: got %s want %s", got, fixture.Scope)
	}
}

func TestFingerprintIgnoresNumberRepresentation(t *testing.T) {
	left, err := Fingerprint("loan.repayment", map[string]any{"loan_id": 8, "amount": 150, "payment_type_id": 4})
	if err != nil {
		t.Fatal(err)
	}
	// encoding/json decodes every number as float64, which is what an MCP call
	// argument looks like.
	right, err := Fingerprint("loan.repayment", map[string]any{"loan_id": 8.0, "amount": 150.0, "payment_type_id": 4.0})
	if err != nil {
		t.Fatal(err)
	}
	if left != right {
		t.Fatalf("integer and float fingerprints differ: %s vs %s", left, right)
	}
}

func TestFingerprintRoundsToTenPlaces(t *testing.T) {
	first, err := Fingerprint("test", map[string]any{"amount": 0.1 + 0.2})
	if err != nil {
		t.Fatal(err)
	}
	second, err := Fingerprint("test", map[string]any{"amount": 0.3})
	if err != nil {
		t.Fatal(err)
	}
	if first != second {
		t.Fatalf("0.1+0.2 should fingerprint as 0.3, got %s vs %s", first, second)
	}
}

func TestCanonicalDoesNotEscapeHTMLOrUnicode(t *testing.T) {
	material, err := Canonical(map[string]any{"text": "<a>&\u2028 café"})
	if err != nil {
		t.Fatal(err)
	}
	want := `{"text":"<a>&` + "\u2028" + ` café"}`
	if material != want {
		t.Fatalf("unexpected canonical text: %q", material)
	}
}

func TestCanonicalRejectsUnsupportedValues(t *testing.T) {
	for name, value := range map[string]any{
		"non-finite": map[string]any{"n": math.Inf(1)},
		"bytes":      []byte("x"),
		"struct":     struct{ A int }{1},
	} {
		if _, err := Canonical(value); err == nil {
			t.Fatalf("%s should be rejected", name)
		}
	}
	if _, err := Fingerprint("test", map[string]any{"n": math.Inf(1)}); err == nil {
		t.Fatal("a non-finite number must not be fingerprinted")
	}
}

func TestReadsPythonWrittenRecord(t *testing.T) {
	fixture := loadVectors(t)
	directory := t.TempDir()
	ledger := NewLedger(directory)
	if err := ledger.EnsureReady(); err != nil {
		t.Fatalf("ensure ledger: %v", err)
	}
	for _, vector := range fixture.Records {
		path := ledger.RecordPath(vector.Record.KeyHash)
		encoded, err := json.Marshal(vector.Record)
		if err != nil {
			t.Fatalf("encode %s: %v", vector.Name, err)
		}
		if err := os.WriteFile(path, encoded, 0o600); err != nil {
			t.Fatalf("write %s: %v", vector.Name, err)
		}
		record, err := ledger.Read(vector.Record.KeyHash)
		if err != nil {
			t.Fatalf("read %s: %v", vector.Name, err)
		}
		if record == nil {
			t.Fatalf("%s was not read back", vector.Name)
		}
		if record.Operation != vector.Record.Operation || record.State != vector.Record.State {
			t.Fatalf("%s round trip changed the record: %+v", vector.Name, record)
		}
		if record.Version != LedgerVersion {
			t.Fatalf("%s version %d", vector.Name, record.Version)
		}
	}
}
