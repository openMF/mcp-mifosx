// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package idempotency

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"
)

// TestClaimStaysAvailableUnderContention guards the release path: the OS lock
// must be released promptly so a queued waiter takes its turn instead of
// timing out with operation_in_progress while the previous owner is idle.
func TestClaimStaysAvailableUnderContention(t *testing.T) {
	ledger := newTestLedger(t)
	ledger.LockWait = 5 * time.Second
	ledger.Poll = 5 * time.Millisecond

	const waiters = 4
	var (
		group    sync.WaitGroup
		mu       sync.Mutex
		acquired []int
	)
	for index := 0; index < waiters; index++ {
		group.Add(1)
		go func(index int) {
			defer group.Done()
			release, err := ledger.Claim(context.Background(), "same-key", fmt.Sprintf("corr-%d", index))
			if err != nil {
				t.Errorf("waiter %d could not claim: %v", index, err)
				return
			}
			mu.Lock()
			acquired = append(acquired, index)
			mu.Unlock()
			// Hold the claim long enough for the other waiters to queue up.
			time.Sleep(20 * time.Millisecond)
			release()
		}(index)
	}
	group.Wait()

	if len(acquired) != waiters {
		t.Fatalf("only %d of %d waiters acquired the claim: %v", len(acquired), waiters, acquired)
	}
	final, err := ledger.Claim(context.Background(), "same-key", "final")
	if err != nil {
		t.Fatalf("the claim was not released: %v", err)
	}
	final()
}
