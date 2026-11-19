// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

//go:build !windows

package idempotency

import (
	"errors"
	"os"
	"syscall"
)

// tryLockFile takes a non-blocking exclusive advisory lock. python/core/
// idempotency.py uses fcntl.flock on the same file, so the two processes
// sharing a ledger directory exclude each other.
func tryLockFile(file *os.File) error {
	return syscall.Flock(int(file.Fd()), syscall.LOCK_EX|syscall.LOCK_NB)
}

func unlockFile(file *os.File) error {
	return syscall.Flock(int(file.Fd()), syscall.LOCK_UN)
}

func isLockContention(err error) bool {
	return errors.Is(err, syscall.EWOULDBLOCK) || errors.Is(err, syscall.EAGAIN)
}
