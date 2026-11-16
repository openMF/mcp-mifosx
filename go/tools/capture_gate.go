// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package tools

// Document capture-quality gate: RETAKE | REVIEW | PASS, deterministic and
// offline.
//
// This is the Go port of python/tools/capture_gate.py. The verdict contract,
// signal vocabulary and v3.1 thresholds are the integration surface shared by
// every MCP server (see contract/capture_gate_vectors.json), so changes here
// must keep the Python gate in step. The gate answers "is the capture
// readable?", never "is the document the right one?" — content verification is
// a separate concern.
//
// Design constraints, unchanged from the Python implementation:
//   - no LLM, no CV/ML library: fixed arithmetic only;
//   - resolution-invariant: every capture is resampled to a 512px analysis
//     grid before measuring;
//   - measured where the content is: sharpness is the gradient inside a 1px
//     dilation of the ink mask, contrast is bg - p25(ink pixels);
//   - deterministic: the same bytes always yield the same verdict.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	// Decoders the gate accepts. Python's allow-list is JPEG, PNG, WEBP and
	// TIFF; registering the same set keeps unreadable-format behavior aligned.
	_ "image/jpeg"
	_ "image/png"

	"github.com/mark3labs/mcp-go/mcp"
	_ "golang.org/x/image/tiff"
	_ "golang.org/x/image/webp"
)

const (
	// AnalysisMaxSide is the fixed analysis grid; thresholds stay
	// resolution-invariant because every capture is measured here.
	AnalysisMaxSide = 512
	// InkMargin marks a pixel as ink when luma < p90(luma) - InkMargin.
	InkMargin = 0.25
	// InkBGPct is the background estimate percentile.
	InkBGPct = 90.0
	// MinInkForContrast is the ink-pixel count below which contrast falls back
	// to whole-frame percentiles.
	MinInkForContrast = 16
	// ContrastPctLo and ContrastPctHi are the whole-frame fallback percentiles.
	ContrastPctLo = 0.5
	ContrastPctHi = 99.5
	// MaxImagePixels rejects decompression bombs before allocating a dense grid.
	MaxImagePixels = 64_000_000

	// v3.1 gate thresholds, supervised on 2000 labelled images + field evidence.
	RetakeBrightMin   = 0.10  // mean luma below this -> too dark to read
	RetakeInkMin      = 0.002 // less ink than this -> blank page / wall / crushed
	RetakeSharpMin    = 0.08  // text-edge strength below this -> unreadably blurry
	ReviewBrightMin   = 0.25  // dim but possibly readable
	ReviewContrastMin = 0.18  // washed-out print / crushed capture
	ReviewInkMin      = 0.003 // near-blank content
	ReviewSharpMin    = 0.13  // soft text edges (mild blur / faint ink)

	VerdictRetake = "RETAKE"
	VerdictReview = "REVIEW"
	VerdictPass   = "PASS"

	// MaxCaptureBytes mirrors the Python path tool's pre-decode cap.
	MaxCaptureBytes = 40 * 1024 * 1024
)

// ErrUnreadableImage is returned when bytes do not decode as a supported
// image or exceed the analysis-safe pixel budget.
var ErrUnreadableImage = errors.New("unreadable image")

// CaptureSignals is the stable signal vocabulary every server returns.
type CaptureSignals struct {
	Sharpness  float64 `json:"sharpness"`
	Contrast   float64 `json:"contrast"`
	InkRatio   float64 `json:"ink_ratio"`
	Brightness float64 `json:"brightness"`
	Exposure   float64 `json:"exposure"`
}

// CaptureGateResult is the gate response. Error is set instead of a verdict
// when the capture could not be assessed (missing / unreadable / outside the
// configured capture directory).
type CaptureGateResult struct {
	Verdict string          `json:"verdict,omitempty"`
	Reasons []string        `json:"reasons,omitempty"`
	Signals *CaptureSignals `json:"signals,omitempty"`
	Error   string          `json:"error,omitempty"`
}

// MarshalJSON keeps the JSON shape identical to the Python gate: an error
// response is exactly {"error": "..."} and a verdict response always carries
// verdict, reasons (possibly []) and signals.
func (r CaptureGateResult) MarshalJSON() ([]byte, error) {
	if r.Error != "" {
		return json.Marshal(struct {
			Error string `json:"error"`
		}{r.Error})
	}
	reasons := r.Reasons
	if reasons == nil {
		reasons = []string{}
	}
	return json.Marshal(struct {
		Verdict string          `json:"verdict"`
		Reasons []string        `json:"reasons"`
		Signals *CaptureSignals `json:"signals"`
	}{r.Verdict, reasons, r.Signals})
}

// AssessImage gates one document capture by its raw bytes.
func AssessImage(data []byte, filename string) (CaptureGateResult, error) {
	// Probe the header first: the Python gate rejects a decompression bomb
	// before building a dense pixel array, so do the same here.
	cfg, _, err := image.DecodeConfig(bytes.NewReader(data))
	if err != nil {
		return CaptureGateResult{}, fmt.Errorf("%w %q (%v)", ErrUnreadableImage, filename, err)
	}
	if cfg.Width <= 0 || cfg.Height <= 0 {
		return CaptureGateResult{}, fmt.Errorf("%w %q (empty image)", ErrUnreadableImage, filename)
	}
	if int64(cfg.Width)*int64(cfg.Height) > MaxImagePixels {
		return CaptureGateResult{}, fmt.Errorf(
			"%w %q (image too large: %dx%d)", ErrUnreadableImage, filename, cfg.Width, cfg.Height)
	}

	img, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return CaptureGateResult{}, fmt.Errorf("%w %q (%v)", ErrUnreadableImage, filename, err)
	}

	bounds := img.Bounds()
	w, h := bounds.Dx(), bounds.Dy()
	if w <= 0 || h <= 0 {
		return CaptureGateResult{}, fmt.Errorf("%w %q (empty image)", ErrUnreadableImage, filename)
	}

	luma := lumaGrid(img, bounds)
	luma, outH, outW := analysisGrid(luma, h, w)
	signals := measureSignals(luma, outH, outW)
	verdict, reasons := Decide(signals)
	return CaptureGateResult{Verdict: verdict, Reasons: reasons, Signals: &signals}, nil
}

// Decide maps gate signals to a verdict and the named reasons it tripped.
//
// RETAKE when a hard gate trips (too dark / blank / unreadable blur); REVIEW
// when only the softer band trips (dim / washed-out / scant / mild blur);
// otherwise PASS with no reasons.
func Decide(s CaptureSignals) (string, []string) {
	reasons := []string{}

	if s.Brightness < RetakeBrightMin {
		reasons = append(reasons, fmt.Sprintf(
			"too dark to read (brightness %.3f < %s)", s.Brightness, threshold(RetakeBrightMin)))
	}
	if s.InkRatio < RetakeInkMin {
		reasons = append(reasons, fmt.Sprintf(
			"no detectable content (ink_ratio %.4f < %s)", s.InkRatio, threshold(RetakeInkMin)))
	} else if s.Sharpness < RetakeSharpMin {
		reasons = append(reasons, fmt.Sprintf(
			"unreadably blurry (sharpness %.3f < %s)", s.Sharpness, threshold(RetakeSharpMin)))
	}
	if len(reasons) > 0 {
		return VerdictRetake, reasons
	}

	if s.Brightness < ReviewBrightMin {
		reasons = append(reasons, fmt.Sprintf(
			"dim but possibly readable (brightness %.3f < %s)", s.Brightness, threshold(ReviewBrightMin)))
	}
	if s.Contrast < ReviewContrastMin {
		reasons = append(reasons, fmt.Sprintf(
			"washed-out print (contrast %.3f < %s)", s.Contrast, threshold(ReviewContrastMin)))
	}
	if s.InkRatio < ReviewInkMin {
		reasons = append(reasons, fmt.Sprintf(
			"scant content (ink_ratio %.4f < %s)", s.InkRatio, threshold(ReviewInkMin)))
	} else if s.Sharpness < ReviewSharpMin {
		reasons = append(reasons, fmt.Sprintf(
			"soft text edges / mild blur (sharpness %.3f < %s)", s.Sharpness, threshold(ReviewSharpMin)))
	}
	if len(reasons) > 0 {
		return VerdictReview, reasons
	}

	return VerdictPass, reasons
}

// ValidateCaptureQuality gates a capture by path, confined to
// MIFOSX_CAPTURE_DIR. Error is set instead of a verdict when the path is
// outside the root or the capture cannot be assessed.
func ValidateCaptureQuality(imagePath string) CaptureGateResult {
	root := os.Getenv("MIFOSX_CAPTURE_DIR")
	if root == "" {
		return CaptureGateResult{Error: "capture directory not allowed"}
	}
	resolvedRoot := realPath(root)
	resolved := realPath(imagePath)
	if resolved != resolvedRoot && !strings.HasPrefix(resolved, resolvedRoot+string(os.PathSeparator)) {
		return CaptureGateResult{Error: "capture directory not allowed"}
	}

	data, err := os.ReadFile(resolved)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return CaptureGateResult{Error: fmt.Sprintf("image not found: %s", imagePath)}
		}
		if info, statErr := os.Stat(resolved); statErr == nil && info.IsDir() {
			return CaptureGateResult{Error: fmt.Sprintf("%s is a directory — pass the photo file", imagePath)}
		}
		return CaptureGateResult{Error: fmt.Sprintf("could not read %s: %v", imagePath, err)}
	}
	if len(data) == 0 {
		return CaptureGateResult{Error: fmt.Sprintf("%s is empty — capture again", imagePath)}
	}
	if len(data) > MaxCaptureBytes {
		return CaptureGateResult{Error: fmt.Sprintf(
			"%s is %d bytes; captures over %d are rejected before reading",
			imagePath, len(data), MaxCaptureBytes)}
	}

	result, err := AssessImage(data, imagePath)
	if err != nil {
		return CaptureGateResult{Error: err.Error()}
	}
	return result
}

// lumaGrid converts an image to a row-major luma grid in [0, 1].
func lumaGrid(img image.Image, bounds image.Rectangle) []float64 {
	w, h := bounds.Dx(), bounds.Dy()
	luma := make([]float64, w*h)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			c := color.NRGBAModel.Convert(img.At(bounds.Min.X+x, bounds.Min.Y+y)).(color.NRGBA)
			luma[y*w+x] = 0.2126*float64(c.R)/255 +
				0.7152*float64(c.G)/255 +
				0.0722*float64(c.B)/255
		}
	}
	return luma
}

// analysisGrid resamples luma to the fixed analysis grid (aspect preserved,
// max side AnalysisMaxSide) with bilinear interpolation.
func analysisGrid(luma []float64, h, w int) ([]float64, int, int) {
	scale := float64(AnalysisMaxSide) / float64(max(h, w))
	outH := max(roundHalfEven(float64(h)*scale), 16)
	outW := max(roundHalfEven(float64(w)*scale), 16)
	if outH == h && outW == w {
		return luma, h, w
	}

	yy := linspace(0, float64(max(h-1, 1)), outH)
	xx := linspace(0, float64(max(w-1, 1)), outW)
	out := make([]float64, outH*outW)
	for oy := 0; oy < outH; oy++ {
		y0 := min(int(math.Floor(yy[oy])), h-1)
		y1 := min(y0+1, h-1)
		wy := yy[oy] - float64(y0)
		for ox := 0; ox < outW; ox++ {
			x0 := min(int(math.Floor(xx[ox])), w-1)
			x1 := min(x0+1, w-1)
			wx := xx[ox] - float64(x0)

			tl := luma[y0*w+x0]
			tr := luma[y0*w+x1]
			bl := luma[y1*w+x0]
			br := luma[y1*w+x1]
			out[oy*outW+ox] = tl*(1-wy)*(1-wx) + tr*(1-wy)*wx +
				bl*wy*(1-wx) + br*wy*wx
		}
	}
	return out, outH, outW
}

// measureSignals computes the five gate signals from the analysis-grid luma.
func measureSignals(luma []float64, h, w int) CaptureSignals {
	grad := gradMag(luma, h, w)
	bg := percentile(luma, InkBGPct)

	inkMask := make([]bool, len(luma))
	inkCount := 0
	inkVals := make([]float64, 0, len(luma))
	for i, v := range luma {
		if v < bg-InkMargin {
			inkMask[i] = true
			inkCount++
			inkVals = append(inkVals, v)
		}
	}
	inkRatio := float64(inkCount) / float64(len(luma))

	near := dilate(inkMask, h, w)
	nearCount := 0
	gradSum := 0.0
	for i, v := range near {
		if v {
			nearCount++
			gradSum += grad[i]
		}
	}

	var sharpness float64
	if nearCount > 0 {
		sharpness = gradSum / float64(nearCount)
	} else {
		sharpness = mean(grad)
	}

	var contrast float64
	if len(inkVals) >= MinInkForContrast {
		contrast = max(bg-percentile(inkVals, 25), 0)
	} else {
		contrast = max(percentile(luma, ContrastPctHi)-percentile(luma, ContrastPctLo), 0)
	}

	brightness := mean(luma)

	return CaptureSignals{
		Sharpness:  round6(sharpness),
		Contrast:   round6(contrast),
		InkRatio:   round6(inkRatio),
		Brightness: round6(brightness),
		Exposure:   round6(math.Abs(brightness - 0.5)),
	}
}

// gradMag returns the per-pixel gradient magnitude, matching np.diff's
// forward differences and the Python gate's 0.5 weighting.
func gradMag(luma []float64, h, w int) []float64 {
	grad := make([]float64, h*w)
	for y := 0; y < h; y++ {
		for x := 1; x < w; x++ {
			grad[y*w+x] += 0.5 * math.Abs(luma[y*w+x]-luma[y*w+x-1])
		}
	}
	for y := 1; y < h; y++ {
		for x := 0; x < w; x++ {
			grad[y*w+x] += 0.5 * math.Abs(luma[y*w+x]-luma[(y-1)*w+x])
		}
	}
	return grad
}

// dilate is a 1px 4-connected dilation with wrap-around, matching np.roll.
func dilate(mask []bool, h, w int) []bool {
	out := make([]bool, len(mask))
	copy(out, mask)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			i := y*w + x
			if out[i] {
				continue
			}
			up := ((y - 1 + h) % h) * w
			down := ((y + 1) % h) * w
			left := (x - 1 + w) % w
			right := (x + 1) % w
			if mask[up+x] || mask[down+x] || mask[y*w+left] || mask[y*w+right] {
				out[i] = true
			}
		}
	}
	return out
}

// percentile matches NumPy's default "linear" interpolation.
func percentile(values []float64, q float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sorted := make([]float64, len(values))
	copy(sorted, values)
	sort.Float64s(sorted)

	pos := float64(len(sorted)-1) * q / 100
	lo := int(math.Floor(pos))
	hi := int(math.Ceil(pos))
	if lo == hi {
		return sorted[lo]
	}
	frac := pos - float64(lo)
	return sorted[lo] + (sorted[hi]-sorted[lo])*frac
}

// linspace matches np.linspace(start, stop, num), including the explicit
// endpoint fix-up so the last sample is exactly stop.
func linspace(start, stop float64, num int) []float64 {
	out := make([]float64, num)
	if num <= 0 {
		return out
	}
	if num == 1 {
		out[0] = start
		return out
	}
	step := (stop - start) / float64(num-1)
	for i := range out {
		out[i] = start + float64(i)*step
	}
	out[num-1] = stop
	return out
}

// roundHalfEven matches Python's round() for the dimension arithmetic.
func roundHalfEven(v float64) int {
	floor := math.Floor(v)
	frac := v - floor
	switch {
	case frac < 0.5:
		return int(floor)
	case frac > 0.5:
		return int(floor) + 1
	default:
		if int64(floor)%2 == 0 {
			return int(floor)
		}
		return int(floor) + 1
	}
}

// round6 matches Python's round(v, 6) closely enough that the shared golden
// vectors agree: both round half to even, and the tests compare signals with
// a small tolerance.
func round6(v float64) float64 {
	return math.RoundToEven(v*1e6) / 1e6
}

func mean(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sum := 0.0
	for _, v := range values {
		sum += v
	}
	return sum / float64(len(values))
}

// threshold renders a constant the way Python's f-string does.
func threshold(v float64) string {
	return strconv.FormatFloat(v, 'g', -1, 64)
}

// realPath resolves symlinks like os.path.realpath(..., strict=False): when
// the leaf does not exist, resolve the parent and keep the base name.
func realPath(p string) string {
	abs, err := filepath.Abs(p)
	if err != nil {
		return p
	}
	if resolved, err := filepath.EvalSymlinks(abs); err == nil {
		return resolved
	}
	dir, base := filepath.Split(abs)
	if resolved, err := filepath.EvalSymlinks(filepath.Clean(dir)); err == nil {
		return filepath.Join(resolved, base)
	}
	return abs
}

// RegisterNativeCaptureTools registers the deterministic capture-quality tool.
func (r *Registry) RegisterNativeCaptureTools() {
	r.registerTools([]BaseToolDef{
		{
			Name: "validate_capture_quality",
			Description: "Gate a document photo BEFORE upload: returns RETAKE | REVIEW | PASS " +
				"with the measured signals and the exact gates that tripped. Deterministic and offline.",
			Params: []ToolParam{
				{Name: "image_path", Type: "string", Required: true,
					Description: "Path to the capture inside MIFOSX_CAPTURE_DIR"},
			},
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args, ok := req.Params.Arguments.(map[string]interface{})
				if !ok {
					return mcp.NewToolResultError("Arguments must be a JSON object"), nil
				}
				imagePath, _ := args["image_path"].(string)
				if imagePath == "" {
					return mcp.NewToolResultError("image_path is required"), nil
				}
				result := ValidateCaptureQuality(imagePath)
				payload, err := json.Marshal(result)
				if err != nil {
					return mcp.NewToolResultError(fmt.Sprintf("could not encode result: %v", err)), nil
				}
				return mcp.NewToolResultText(string(payload)), nil
			},
		},
	})
}
