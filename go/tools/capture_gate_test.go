// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/png"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"slices"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/mark3labs/mcp-go/mcp"
	mcpserver "github.com/mark3labs/mcp-go/server"
	"github.com/openMF/mcp-mifosx/go/adapter"
)

// contractFile mirrors contract/capture/golden_vectors.json, the language-neutral
// fixture set every server's capture gate is tested against.
const contractFile = "../../contract/capture/golden_vectors.json"

type gateVectorContract struct {
	SignalTolerance float64 `json:"signal_tolerance"`
	Cases           []struct {
		Name     string `json:"name"`
		Expected struct {
			Verdict string         `json:"verdict"`
			Reasons []string       `json:"reasons"`
			Signals CaptureSignals `json:"signals"`
		} `json:"expected"`
		ImageBase64 string `json:"image_base64"`
	} `json:"cases"`
}

// TestGoldenVectors is the cross-language contract: the Go gate must reproduce
// the verdict and signals Python recorded for every shared fixture.
func TestGoldenVectors(t *testing.T) {
	raw, err := os.ReadFile(contractFile)
	if err != nil {
		t.Fatalf("read shared contract: %v", err)
	}
	var contract gateVectorContract
	if err := json.Unmarshal(raw, &contract); err != nil {
		t.Fatalf("parse shared contract: %v", err)
	}
	if len(contract.Cases) == 0 {
		t.Fatal("shared contract has no cases")
	}

	seen := map[string]bool{}
	for _, tc := range contract.Cases {
		t.Run(tc.Name, func(t *testing.T) {
			data, err := base64.StdEncoding.DecodeString(tc.ImageBase64)
			if err != nil {
				t.Fatalf("decode fixture: %v", err)
			}
			got, err := AssessImage(data, tc.Name+".png")
			if err != nil {
				t.Fatalf("assess fixture: %v", err)
			}

			if got.Verdict != tc.Expected.Verdict {
				t.Errorf("verdict = %q, want %q (reasons: %v)", got.Verdict, tc.Expected.Verdict, got.Reasons)
			}
			if !slices.Equal(got.Reasons, tc.Expected.Reasons) {
				t.Errorf("reasons = %v, want %v", got.Reasons, tc.Expected.Reasons)
			}
			if got.Signals == nil {
				t.Fatal("signals missing")
			}
			assertSignal := func(name string, got, want float64) {
				t.Helper()
				if math.Abs(got-want) > contract.SignalTolerance {
					t.Errorf("%s = %.9f, want %.9f (±%g)", name, got, want, contract.SignalTolerance)
				}
			}
			assertSignal("sharpness", got.Signals.Sharpness, tc.Expected.Signals.Sharpness)
			assertSignal("contrast", got.Signals.Contrast, tc.Expected.Signals.Contrast)
			assertSignal("ink_ratio", got.Signals.InkRatio, tc.Expected.Signals.InkRatio)
			assertSignal("brightness", got.Signals.Brightness, tc.Expected.Signals.Brightness)
			assertSignal("exposure", got.Signals.Exposure, tc.Expected.Signals.Exposure)
			seen[got.Verdict] = true
		})
	}

	for _, verdict := range []string{VerdictRetake, VerdictReview, VerdictPass} {
		if !seen[verdict] {
			t.Errorf("shared contract exercises no %s fixture", verdict)
		}
	}
}

// TestDecideV31Boundaries pins the published bands: hard RETAKE below 0.08
// sharpness, REVIEW in 0.08–0.13, PASS at or above 0.13.
func TestDecideV31Boundaries(t *testing.T) {
	readable := CaptureSignals{
		Sharpness: 0.20, Contrast: 0.50, InkRatio: 0.01, Brightness: 0.95, Exposure: 0.45,
	}

	with := func(mutate func(*CaptureSignals)) CaptureSignals {
		s := readable
		mutate(&s)
		return s
	}

	cases := []struct {
		name    string
		signals CaptureSignals
		want    string
	}{
		{"sharpness 0.05 is destroyed", with(func(s *CaptureSignals) { s.Sharpness = 0.05 }), VerdictRetake},
		{"sharpness 0.079 is destroyed", with(func(s *CaptureSignals) { s.Sharpness = 0.079 }), VerdictRetake},
		{"sharpness 0.08 is readable-blur band", with(func(s *CaptureSignals) { s.Sharpness = 0.08 }), VerdictReview},
		{"sharpness 0.088 is the field-anchored case", with(func(s *CaptureSignals) { s.Sharpness = 0.088 }), VerdictReview},
		{"sharpness 0.129 is still soft", with(func(s *CaptureSignals) { s.Sharpness = 0.129 }), VerdictReview},
		{"sharpness 0.13 passes", with(func(s *CaptureSignals) { s.Sharpness = 0.13 }), VerdictPass},
		{"brightness 0.09 is too dark", with(func(s *CaptureSignals) { s.Brightness = 0.09 }), VerdictRetake},
		{"brightness 0.24 is dim", with(func(s *CaptureSignals) { s.Brightness = 0.24 }), VerdictReview},
		{"brightness 0.25 is bright enough", with(func(s *CaptureSignals) { s.Brightness = 0.25 }), VerdictPass},
		{"contrast 0.17 is washed out", with(func(s *CaptureSignals) { s.Contrast = 0.17 }), VerdictReview},
		{"contrast 0.18 is acceptable", with(func(s *CaptureSignals) { s.Contrast = 0.18 }), VerdictPass},
		{"ink_ratio 0.001 is blank", with(func(s *CaptureSignals) { s.InkRatio = 0.001 }), VerdictRetake},
		{"ink_ratio 0.0025 is scant", with(func(s *CaptureSignals) { s.InkRatio = 0.0025 }), VerdictReview},
		{"ink_ratio 0.003 is enough content", with(func(s *CaptureSignals) { s.InkRatio = 0.003 }), VerdictPass},
		{"crisp capture passes", readable, VerdictPass},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, reasons := Decide(tc.signals)
			if got != tc.want {
				t.Fatalf("Decide(%+v) = %q, want %q (reasons: %v)", tc.signals, got, tc.want, reasons)
			}
			if tc.want == VerdictPass && len(reasons) != 0 {
				t.Errorf("PASS carried reasons: %v", reasons)
			}
			if tc.want != VerdictPass && len(reasons) == 0 {
				t.Errorf("%s carried no reasons", tc.want)
			}
		})
	}
}

func TestAssessImageIsDeterministic(t *testing.T) {
	data := encodeTestPNG(t, crispTestImage())

	first, err := AssessImage(data, "capture.png")
	if err != nil {
		t.Fatalf("first assessment: %v", err)
	}
	second, err := AssessImage(data, "capture.png")
	if err != nil {
		t.Fatalf("second assessment: %v", err)
	}
	if first.Verdict != second.Verdict || !slices.Equal(first.Reasons, second.Reasons) {
		t.Fatalf("verdict drifted: %+v then %+v", first, second)
	}
	if *first.Signals != *second.Signals {
		t.Fatalf("signals drifted: %+v then %+v", first.Signals, second.Signals)
	}
}

func TestAssessImageRejectsUnreadableBytes(t *testing.T) {
	_, err := AssessImage([]byte("not an image at all"), "bad.png")
	if !errors.Is(err, ErrUnreadableImage) {
		t.Fatalf("error = %v, want ErrUnreadableImage", err)
	}
}

func TestValidateCaptureQuality(t *testing.T) {
	good := base64.StdEncoding.EncodeToString(encodeTestPNG(t, crispTestImage()))
	corrupt := base64.StdEncoding.EncodeToString([]byte("corrupt"))
	empty := base64.StdEncoding.EncodeToString(nil)

	t.Run("crisp capture passes", func(t *testing.T) {
		got := ValidateCaptureQuality(good, "good.png")
		if got.Error != "" {
			t.Fatalf("unexpected error: %s", got.Error)
		}
		if got.Verdict != VerdictPass {
			t.Fatalf("verdict = %q, want PASS", got.Verdict)
		}
	})

	t.Run("filename is optional", func(t *testing.T) {
		got := ValidateCaptureQuality(good, "")
		if got.Error != "" || got.Verdict != VerdictPass {
			t.Fatalf("result = %+v, want PASS without a filename", got)
		}
	})

	t.Run("empty capture", func(t *testing.T) {
		got := ValidateCaptureQuality(empty, "empty.png")
		if got.Error == "" {
			t.Fatal("empty capture produced a verdict")
		}
		if !strings.Contains(got.Error, "empty") {
			t.Fatalf("error = %q, want the empty-capture sentinel", got.Error)
		}
	})

	t.Run("invalid base64 is rejected", func(t *testing.T) {
		got := ValidateCaptureQuality("!!! not base64 !!!", "bad.png")
		if got.Error == "" {
			t.Fatal("invalid base64 produced a verdict")
		}
		if !strings.Contains(got.Error, "not valid base64") {
			t.Fatalf("error = %q, want the base64 sentinel", got.Error)
		}
	})

	t.Run("oversized capture is rejected before decoding", func(t *testing.T) {
		// Length-checked against maxBase64Chars, so the 40 MB limit is
		// enforced without allocating the decoded bytes.
		big := strings.Repeat("A", maxBase64Chars+1)
		got := ValidateCaptureQuality(big, "big.png")
		if !strings.Contains(got.Error, "capture limit") {
			t.Fatalf("error = %q, want the capture-limit sentinel", got.Error)
		}
	})

	t.Run("corrupt capture", func(t *testing.T) {
		got := ValidateCaptureQuality(corrupt, "bad.png")
		if got.Error == "" {
			t.Fatal("corrupt capture produced a verdict")
		}
		if got.Verdict != "" {
			t.Fatalf("corrupt capture carried verdict %q", got.Verdict)
		}
	})
}

// TestResultJSONMatchesPythonShape keeps the MCP payload identical to the
// Python gate: verdict responses always carry reasons, errors carry only error.
func TestResultJSONMatchesPythonShape(t *testing.T) {
	pass := CaptureGateResult{
		Verdict: VerdictPass,
		Reasons: []string{},
		Signals: &CaptureSignals{},
	}
	raw, err := json.Marshal(pass)
	if err != nil {
		t.Fatalf("marshal verdict: %v", err)
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(raw, &fields); err != nil {
		t.Fatalf("unmarshal verdict: %v", err)
	}
	for _, key := range []string{"verdict", "reasons", "signals"} {
		if _, ok := fields[key]; !ok {
			t.Errorf("verdict payload omits %q: %s", key, raw)
		}
	}
	if string(fields["reasons"]) != "[]" {
		t.Errorf("PASS reasons = %s, want []", fields["reasons"])
	}

	raw, err = json.Marshal(CaptureGateResult{Error: "capture is empty — capture again"})
	if err != nil {
		t.Fatalf("marshal error: %v", err)
	}
	if want := `{"error":"capture is empty — capture again"}`; string(raw) != want {
		t.Errorf("error payload = %s, want %s", raw, want)
	}
}

func TestRegisterNativeCaptureTools(t *testing.T) {
	registry := &Registry{Server: mcpserver.NewMCPServer("capture-test", "1")}
	registry.RegisterNativeCaptureTools()

	def, ok := registry.ToolDefs["validate_capture_quality"]
	if !ok {
		t.Fatal("validate_capture_quality was not registered")
	}
	if len(def.Params) != 2 || def.Params[0].Name != "image_base64" || !def.Params[0].Required {
		t.Fatalf("unexpected params: %+v", def.Params)
	}
	if def.Params[1].Name != "filename" || def.Params[1].Required {
		t.Fatalf("unexpected filename param: %+v", def.Params[1])
	}
	if def.Handler == nil {
		t.Fatal("tool has no handler")
	}

	upload, ok := registry.ToolDefs["upload_client_document"]
	if !ok {
		t.Fatal("upload_client_document was not registered")
	}
	wantParams := map[string]bool{"client_id": true, "image_base64": true}
	for _, p := range upload.Params {
		if wantParams[p.Name] {
			if !p.Required {
				t.Errorf("%s should be required", p.Name)
			}
			delete(wantParams, p.Name)
		}
	}
	if len(wantParams) > 0 {
		t.Errorf("missing params: %v", wantParams)
	}
	if upload.Handler == nil {
		t.Fatal("upload tool has no handler")
	}
}

// TestUploadClientDocumentGatedFlow covers Victor's decision: the standalone
// validation tool always works, and the gate around upload_client_document is
// enabled only by MIFOSX_CAPTURE_GATE_ON_UPLOAD=true.
func TestUploadClientDocumentGatedFlow(t *testing.T) {
	var uploads atomic.Int32
	var gotFilename string
	var gotContent []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		uploads.Add(1)
		if r.Method != http.MethodPost || !strings.HasSuffix(r.URL.Path, "/clients/42/documents") {
			http.Error(w, "unexpected request", http.StatusNotFound)
			return
		}
		if err := r.ParseMultipartForm(1 << 20); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		file, header, err := r.FormFile("file")
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		defer file.Close()
		gotFilename = header.Filename
		gotContent, _ = io.ReadAll(file)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"officeId":1,"clientId":42,"resourceId":99}`)
	}))
	defer srv.Close()

	registry := &Registry{
		Server: mcpserver.NewMCPServer("capture-gate-test", "1"),
		Fineract: &adapter.FineractClient{
			BaseURL: srv.URL, Username: "mifos", Password: "password",
			TenantID: "default", HTTP: srv.Client(),
		},
	}
	registry.RegisterNativeCaptureTools()
	handler := registry.ToolDefs["upload_client_document"].Handler

	call := func(t *testing.T, args map[string]interface{}) *mcp.CallToolResult {
		t.Helper()
		result, err := handler(context.Background(), mcp.CallToolRequest{
			Params: mcp.CallToolParams{Name: "upload_client_document", Arguments: args},
		})
		if err != nil {
			t.Fatalf("handler error: %v", err)
		}
		if result == nil || len(result.Content) == 0 {
			t.Fatal("handler returned no content")
		}
		return result
	}
	text := func(t *testing.T, result *mcp.CallToolResult) string {
		t.Helper()
		tc, ok := result.Content[0].(mcp.TextContent)
		if !ok {
			t.Fatalf("content is not text: %#v", result.Content[0])
		}
		return tc.Text
	}

	crisp := base64.StdEncoding.EncodeToString(encodeTestPNG(t, crispTestImage()))
	// A blank page: brightness/ink both fail, so the verdict is RETAKE.
	blank := base64.StdEncoding.EncodeToString(encodeTestPNG(t, blankTestImage()))

	t.Run("gate off uploads without assessing", func(t *testing.T) {
		t.Setenv("MIFOSX_CAPTURE_GATE_ON_UPLOAD", "")
		uploads.Store(0)
		gotFilename, gotContent = "", nil

		result := call(t, map[string]interface{}{
			"client_id": "42", "image_base64": blank, "filename": "kyc.png",
		})
		body := text(t, result)
		if result.IsError {
			t.Fatalf("ungated upload errored: %s", body)
		}
		if uploads.Load() != 1 {
			t.Fatalf("uploads = %d, want 1", uploads.Load())
		}
		if strings.Contains(body, "capture_gate") {
			t.Errorf("ungated response carries a gate audit: %s", body)
		}
		if gotFilename != "kyc.png" || !bytes.Equal(gotContent, mustDecode(t, blank)) {
			t.Errorf("server received filename=%q bytes=%d, want kyc.png/%d bytes",
				gotFilename, len(gotContent), len(mustDecode(t, blank)))
		}
	})

	t.Run("gate on refuses RETAKE before any upload", func(t *testing.T) {
		t.Setenv("MIFOSX_CAPTURE_GATE_ON_UPLOAD", "true")
		uploads.Store(0)

		result := call(t, map[string]interface{}{
			"client_id": "42", "image_base64": blank, "filename": "kyc.png",
		})
		body := text(t, result)
		var refusal struct {
			UploadRejected bool   `json:"upload_rejected"`
			Verdict        string `json:"verdict"`
		}
		if err := json.Unmarshal([]byte(body), &refusal); err != nil {
			t.Fatalf("refusal payload %q: %v", body, err)
		}
		if !refusal.UploadRejected || refusal.Verdict != VerdictRetake {
			t.Errorf("refusal = %+v, want upload_rejected with RETAKE", refusal)
		}
		if uploads.Load() != 0 {
			t.Fatalf("refused capture reached Fineract: %d uploads", uploads.Load())
		}
	})

	t.Run("gate on force=true overrides the refusal", func(t *testing.T) {
		t.Setenv("MIFOSX_CAPTURE_GATE_ON_UPLOAD", "true")
		uploads.Store(0)
		gotFilename, gotContent = "", nil

		result := call(t, map[string]interface{}{
			"client_id": "42", "image_base64": blank, "filename": "kyc.png", "force": true,
		})
		body := text(t, result)
		if result.IsError {
			t.Fatalf("forced upload errored: %s", body)
		}
		if uploads.Load() != 1 {
			t.Fatalf("uploads = %d, want 1", uploads.Load())
		}
		if !strings.Contains(body, `"capture_gate"`) {
			t.Errorf("forced response carries no gate audit: %s", body)
		}
		if !bytes.Equal(gotContent, mustDecode(t, blank)) {
			t.Error("forced upload did not send the assessed bytes")
		}
	})

	t.Run("gate on lets a crisp capture through with the verdict", func(t *testing.T) {
		t.Setenv("MIFOSX_CAPTURE_GATE_ON_UPLOAD", "true")
		uploads.Store(0)

		result := call(t, map[string]interface{}{
			"client_id": "42", "image_base64": crisp, "filename": "kyc.png",
		})
		body := text(t, result)
		if result.IsError {
			t.Fatalf("crisp upload errored: %s", body)
		}
		if uploads.Load() != 1 {
			t.Fatalf("uploads = %d, want 1", uploads.Load())
		}
		if !strings.Contains(body, `"capture_gate"`) || !strings.Contains(body, VerdictPass) {
			t.Errorf("response missing the PASS audit: %s", body)
		}
	})

	t.Run("invalid base64 never reaches Fineract", func(t *testing.T) {
		t.Setenv("MIFOSX_CAPTURE_GATE_ON_UPLOAD", "true")
		uploads.Store(0)

		result := call(t, map[string]interface{}{
			"client_id": "42", "image_base64": "!!! not base64 !!!", "filename": "kyc.png",
		})
		body := text(t, result)
		if !strings.Contains(body, "not valid base64") {
			t.Errorf("error payload = %s", body)
		}
		if uploads.Load() != 0 {
			t.Fatalf("invalid capture reached Fineract: %d uploads", uploads.Load())
		}
	})
}

func mustDecode(t *testing.T, b64 string) []byte {
	t.Helper()
	data, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		t.Fatalf("decode fixture: %v", err)
	}
	return data
}

// blankTestImage builds a page with no readable content: both the RETAKE
// brightness and ink gates trip.
func blankTestImage() image.Image {
	im := image.NewGray(image.Rect(0, 0, 320, 240))
	for y := 0; y < 240; y++ {
		for x := 0; x < 320; x++ {
			im.SetGray(x, y, color.Gray{Y: 0})
		}
	}
	return im
}

// crispTestImage builds a small readable page without depending on the
// contract fixtures, for base64 and determinism tests.
func crispTestImage() image.Image {
	im := image.NewGray(image.Rect(0, 0, 320, 240))
	for y := 0; y < 240; y++ {
		for x := 0; x < 320; x++ {
			im.SetGray(x, y, color.Gray{Y: 245})
		}
	}
	for y := 40; y < 220; y += 24 {
		for x := 30; x < 290; x++ {
			for dy := 0; dy < 2; dy++ {
				im.SetGray(x, y+dy, color.Gray{Y: 20})
			}
		}
	}
	return im
}

func encodeTestPNG(t *testing.T, img image.Image) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatalf("encode PNG: %v", err)
	}
	return buf.Bytes()
}
