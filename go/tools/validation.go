// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"fmt"
	"strconv"
)

func FormatAmount(amount interface{}) (string, error) {
	if amount == nil {
		return "", fmt.Errorf("amount cannot be nil")
	}

	var val float64
	switch v := amount.(type) {
	case float64:
		val = v
	case int:
		val = float64(v)
	case int64:
		val = float64(v)
	case string:
		var err error
		val, err = strconv.ParseFloat(v, 64)
		if err != nil {
			return "", fmt.Errorf("invalid amount string: %v", v)
		}
	default:
		return "", fmt.Errorf("unsupported amount type: %T", v)
	}

	return fmt.Sprintf("%.2f", val), nil
}
