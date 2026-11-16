// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"image"
	"image/color"
	"image/png"
	"math"
	"os"
	"path/filepath"
	"slices"
	"testing"

	mcpserver "github.com/mark3labs/mcp-go/server"
)

// contractFile mirrors contract/capture_gate_vectors.json, the language-neutral
// fixture set every server's capture gate is tested against.
const contractFile = "../../contract/capture_gate_vectors.json"

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
	root := t.TempDir()
	t.Setenv("MIFOSX_CAPTURE_DIR", root)

	good := filepath.Join(root, "good.png")
	if err := os.WriteFile(good, encodeTestPNG(t, crispTestImage()), 0o600); err != nil {
		t.Fatalf("write fixture: %v", err)
	}
	corrupt := filepath.Join(root, "bad.png")
	if err := os.WriteFile(corrupt, []byte("corrupt"), 0o600); err != nil {
		t.Fatalf("write corrupt fixture: %v", err)
	}
	empty := filepath.Join(root, "empty.png")
	if err := os.WriteFile(empty, nil, 0o600); err != nil {
		t.Fatalf("write empty fixture: %v", err)
	}

	t.Run("crisp capture passes", func(t *testing.T) {
		got := ValidateCaptureQuality(good)
		if got.Error != "" {
			t.Fatalf("unexpected error: %s", got.Error)
		}
		if got.Verdict != VerdictPass {
			t.Fatalf("verdict = %q, want PASS", got.Verdict)
		}
	})

	t.Run("unset capture root fails closed", func(t *testing.T) {
		t.Setenv("MIFOSX_CAPTURE_DIR", "")
		if got := ValidateCaptureQuality(good); got.Error != "capture directory not allowed" {
			t.Fatalf("error = %q", got.Error)
		}
	})

	t.Run("outside capture root is rejected", func(t *testing.T) {
		outside := filepath.Join(t.TempDir(), "outside.png")
		if err := os.WriteFile(outside, []byte("x"), 0o600); err != nil {
			t.Fatalf("write outside fixture: %v", err)
		}
		if got := ValidateCaptureQuality(outside); got.Error != "capture directory not allowed" {
			t.Fatalf("error = %q", got.Error)
		}
	})

	t.Run("traversal outside capture root is rejected", func(t *testing.T) {
		if got := ValidateCaptureQuality(root + "/../escape.png"); got.Error != "capture directory not allowed" {
			t.Fatalf("error = %q", got.Error)
		}
	})

	t.Run("missing capture", func(t *testing.T) {
		want := "image not found: " + filepath.Join(root, "nope.png")
		if got := ValidateCaptureQuality(filepath.Join(root, "nope.png")); got.Error != want {
			t.Fatalf("error = %q, want %q", got.Error, want)
		}
	})

	t.Run("directory is not a capture", func(t *testing.T) {
		got := ValidateCaptureQuality(root)
		if got.Error == "" {
			t.Fatal("directory was accepted as a capture")
		}
	})

	t.Run("empty capture", func(t *testing.T) {
		got := ValidateCaptureQuality(empty)
		if got.Error != empty+" is empty — capture again" {
			t.Fatalf("error = %q", got.Error)
		}
	})

	t.Run("corrupt capture", func(t *testing.T) {
		got := ValidateCaptureQuality(corrupt)
		if got.Error == "" {
			t.Fatal("corrupt capture produced a verdict")
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

	raw, err = json.Marshal(CaptureGateResult{Error: "capture directory not allowed"})
	if err != nil {
		t.Fatalf("marshal error: %v", err)
	}
	if want := `{"error":"capture directory not allowed"}`; string(raw) != want {
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
	if len(def.Params) != 1 || def.Params[0].Name != "image_path" || !def.Params[0].Required {
		t.Fatalf("unexpected params: %+v", def.Params)
	}
	if def.Handler == nil {
		t.Fatal("tool has no handler")
	}
}

// crispTestImage builds a small readable page without depending on the
// contract fixtures, for path and determinism tests.
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
