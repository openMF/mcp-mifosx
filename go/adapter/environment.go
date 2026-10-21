// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package adapter

import (
	"errors"
	"fmt"
	"os"

	"github.com/joho/godotenv"
)

// LoadEnvironment loads the optional .env file before mode or client selection.
// Missing files are allowed; other failures must stop initialization.
func LoadEnvironment() error {
	if err := godotenv.Load(); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		return fmt.Errorf("load .env: %w", environmentLoadError{cause: err})
	}
	return nil
}

// Parser errors may quote secret values. Preserve the cause for inspection,
// but keep file contents out of the error's printable diagnostic.
type environmentLoadError struct{ cause error }

func (environmentLoadError) Error() string {
	return "could not read or parse environment file; check permissions and syntax"
}

func (e environmentLoadError) Unwrap() error { return e.cause }
