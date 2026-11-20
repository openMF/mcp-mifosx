// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Package idempotency implements the durable, cross-language idempotency
// ledger used by money-moving MCP tool calls.
//
// The on-disk format and the canonical-JSON fingerprint are shared with
// python/core/idempotency.py. Neither side may change one without the other:
// the compatibility vectors in testdata/idempotency_vectors.json are read by
// both test suites.
package idempotency

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"math/big"
	"os"
	"reflect"
	"sort"
	"strconv"
	"strings"
)

// Canonical renders a value as deterministic JSON text.
//
// Numbers are rounded to 10 decimal places and trailing zeros are removed, so
// an integer and the same value decoded as a float fingerprint identically in
// Python (int/float) and Go (float64 from encoding/json). Map keys are sorted.
func Canonical(value any) (string, error) {
	var b strings.Builder
	if err := writeCanonical(&b, value); err != nil {
		return "", err
	}
	return b.String(), nil
}

func writeCanonical(b *strings.Builder, value any) error {
	switch v := value.(type) {
	case nil:
		b.WriteString("null")
		return nil
	case bool:
		if v {
			b.WriteString("true")
		} else {
			b.WriteString("false")
		}
		return nil
	case string:
		writeJSONString(b, v)
		return nil
	case json.Number:
		return writeNumberText(b, v.String())
	case int:
		b.WriteString(strconv.FormatInt(int64(v), 10))
		return nil
	case int8:
		b.WriteString(strconv.FormatInt(int64(v), 10))
		return nil
	case int16:
		b.WriteString(strconv.FormatInt(int64(v), 10))
		return nil
	case int32:
		b.WriteString(strconv.FormatInt(int64(v), 10))
		return nil
	case int64:
		b.WriteString(strconv.FormatInt(v, 10))
		return nil
	case uint:
		b.WriteString(strconv.FormatUint(uint64(v), 10))
		return nil
	case uint8:
		b.WriteString(strconv.FormatUint(uint64(v), 10))
		return nil
	case uint16:
		b.WriteString(strconv.FormatUint(uint64(v), 10))
		return nil
	case uint32:
		b.WriteString(strconv.FormatUint(uint64(v), 10))
		return nil
	case uint64:
		b.WriteString(strconv.FormatUint(v, 10))
		return nil
	case float32:
		return writeFloat(b, float64(v))
	case float64:
		return writeFloat(b, v)
	case []any:
		return writeSlice(b, v)
	case map[string]any:
		return writeMap(b, v)
	case []byte:
		return fmt.Errorf("idempotency payload does not support []byte")
	}

	return writeReflect(b, reflect.ValueOf(value))
}

func writeNumberText(b *strings.Builder, text string) error {
	if strings.ContainsAny(text, ".eE") {
		parsed, err := strconv.ParseFloat(text, 64)
		if err != nil {
			return fmt.Errorf("idempotency payload has an invalid number %q", text)
		}
		return writeFloat(b, parsed)
	}
	integer, ok := new(big.Int).SetString(text, 10)
	if !ok {
		return fmt.Errorf("idempotency payload has an invalid number %q", text)
	}
	b.WriteString(integer.String())
	return nil
}

func writeFloat(b *strings.Builder, value float64) error {
	if math.IsNaN(value) || math.IsInf(value, 0) {
		return fmt.Errorf("idempotency payload contains a non-finite number")
	}
	if value == 0 {
		b.WriteString("0")
		return nil
	}
	text := strings.TrimRight(strconv.FormatFloat(value, 'f', 10, 64), "0")
	text = strings.TrimRight(text, ".")
	if text == "" || text == "-0" {
		text = "0"
	}
	b.WriteString(text)
	return nil
}

func writeSlice(b *strings.Builder, values []any) error {
	b.WriteByte('[')
	for index, item := range values {
		if index > 0 {
			b.WriteByte(',')
		}
		if err := writeCanonical(b, item); err != nil {
			return err
		}
	}
	b.WriteByte(']')
	return nil
}

func writeMap(b *strings.Builder, values map[string]any) error {
	keys := make([]string, 0, len(values))
	for key := range values {
		keys = append(keys, key)
	}
	sort.Strings(keys)

	b.WriteByte('{')
	for index, key := range keys {
		if index > 0 {
			b.WriteByte(',')
		}
		writeJSONString(b, key)
		b.WriteByte(':')
		if err := writeCanonical(b, values[key]); err != nil {
			return err
		}
	}
	b.WriteByte('}')
	return nil
}

func writeReflect(b *strings.Builder, value reflect.Value) error {
	if !value.IsValid() {
		b.WriteString("null")
		return nil
	}
	for value.Kind() == reflect.Interface || value.Kind() == reflect.Pointer {
		if value.IsNil() {
			b.WriteString("null")
			return nil
		}
		value = value.Elem()
	}

	switch value.Kind() {
	case reflect.Bool:
		return writeCanonical(b, value.Bool())
	case reflect.String:
		writeJSONString(b, value.String())
		return nil
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		b.WriteString(strconv.FormatInt(value.Int(), 10))
		return nil
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
		b.WriteString(strconv.FormatUint(value.Uint(), 10))
		return nil
	case reflect.Float32, reflect.Float64:
		return writeFloat(b, value.Float())
	case reflect.Slice, reflect.Array:
		if value.Type().Elem().Kind() == reflect.Uint8 {
			return fmt.Errorf("idempotency payload does not support []byte")
		}
		b.WriteByte('[')
		for index := 0; index < value.Len(); index++ {
			if index > 0 {
				b.WriteByte(',')
			}
			if err := writeCanonical(b, value.Index(index).Interface()); err != nil {
				return err
			}
		}
		b.WriteByte(']')
		return nil
	case reflect.Map:
		if value.Type().Key().Kind() != reflect.String {
			return fmt.Errorf("idempotency payload keys must be strings")
		}
		converted := make(map[string]any, value.Len())
		iter := value.MapRange()
		for iter.Next() {
			converted[iter.Key().String()] = iter.Value().Interface()
		}
		return writeMap(b, converted)
	}
	return fmt.Errorf("unsupported idempotency payload type: %s", value.Type())
}

// writeJSONString mirrors Python's json.dumps(ensure_ascii=False): control
// characters are escaped, everything else is emitted as UTF-8. This matters
// because Go's encoding/json additionally escapes HTML characters and
// U+2028/U+2029, which would change the fingerprint of ordinary text.
func writeJSONString(b *strings.Builder, value string) {
	b.WriteByte('"')
	for _, r := range value {
		switch r {
		case '"':
			b.WriteString(`\"`)
		case '\\':
			b.WriteString(`\\`)
		case '\b':
			b.WriteString(`\b`)
		case '\f':
			b.WriteString(`\f`)
		case '\n':
			b.WriteString(`\n`)
		case '\r':
			b.WriteString(`\r`)
		case '\t':
			b.WriteString(`\t`)
		default:
			if r < 0x20 {
				fmt.Fprintf(b, `\u%04x`, r)
			} else {
				b.WriteRune(r)
			}
		}
	}
	b.WriteByte('"')
}

// Fingerprint is SHA-256 over the canonical operation + payload.
func Fingerprint(operation string, payload any) (string, error) {
	material, err := Canonical(map[string]any{"operation": operation, "payload": payload})
	if err != nil {
		return "", err
	}
	return sha256Hex(material), nil
}

// KeyHash is the opaque file name for (scope, operation, key).
func KeyHash(scope, operation, key string) string {
	return sha256Hex(fmt.Sprintf("v1|%s|%s|%s", scope, operation, key))
}

// BackendKey is the deterministic Fineract Idempotency-Key for one request.
func BackendKey(scope, operation, key string) string {
	digest := sha256Hex(fmt.Sprintf("%s\n%s\n%s", scope, operation, key))
	return "mcp-" + digest[:48]
}

// DefaultScope mirrors python/core/idempotency.default_scope.
func DefaultScope() string {
	tenant := strings.TrimSpace(os.Getenv("MIFOSX_TENANT_ID"))
	if tenant == "" {
		tenant = "default"
	}
	actor := os.Getenv("MCP_IDEMPOTENCY_ACTOR")
	if actor == "" {
		actor = os.Getenv("MIFOSX_USERNAME")
	}
	if actor == "" {
		actor = "anonymous"
	}
	return fmt.Sprintf("tenant:%s|actor:%s", tenant, sha256Hex(actor)[:16])
}

// BackendHeaderName is Fineract's idempotency header (configurable server-side).
func BackendHeaderName() string {
	name := strings.TrimSpace(os.Getenv("MIFOSX_IDEMPOTENCY_HEADER"))
	if name == "" {
		name = "Idempotency-Key"
	}
	return name
}

func sha256Hex(value string) string {
	sum := sha256.Sum256([]byte(value))
	return hex.EncodeToString(sum[:])
}
