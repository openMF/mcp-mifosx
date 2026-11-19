// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

//go:build windows

package idempotency

import (
	"errors"
	"os"

	"golang.org/x/sys/windows"
)

// tryLockFile takes a non-blocking exclusive lock on byte 0 of the file. The
// same byte is locked by python/core/idempotency.py via msvcrt.locking, so a
// Python and a Go process sharing a ledger directory exclude each other.
func tryLockFile(file *os.File) error {
	overlapped := new(windows.Overlapped)
	return windows.LockFileEx(
		windows.Handle(file.Fd()),
		windows.LOCKFILE_EXCLUSIVE_LOCK|windows.LOCKFILE_FAIL_IMMEDIATELY,
		0,
		1,
		0,
		overlapped,
	)
}

func unlockFile(file *os.File) error {
	overlapped := new(windows.Overlapped)
	return windows.UnlockFileEx(windows.Handle(file.Fd()), 0, 1, 0, overlapped)
}

func isLockContention(err error) bool {
	return errors.Is(err, windows.ERROR_LOCK_VIOLATION) ||
		errors.Is(err, windows.ERROR_IO_PENDING) ||
		errors.Is(err, windows.ERROR_ACCESS_DENIED)
}
