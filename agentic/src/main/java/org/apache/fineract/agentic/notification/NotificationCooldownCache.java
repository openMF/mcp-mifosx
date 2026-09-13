/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.fineract.agentic.notification;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concurrent cooldown cache for notification delivery attempts.
 *
 * <p>The cache stores expiry timestamps in {@link #entries}, uses {@link #ttl} to determine when
 * keys leave cooldown, applies a best-effort {@link #maximumSize} cap, and bases all time checks on
 * the configured {@link #clock}. It is safe for concurrent access and is intended for short-lived
 * in-memory suppression of duplicate notification events.
 */
public class NotificationCooldownCache {

  private final Map<String, Instant> entries = new ConcurrentHashMap<>();
  private final Duration ttl;
  private final int maximumSize;
  private final Clock clock;

  /**
   * Creates a cooldown cache using the system UTC clock.
   *
   * @param ttl positive cooldown duration
   * @param maximumSize positive best-effort maximum cache size
   */
  public NotificationCooldownCache(Duration ttl, int maximumSize) {
    this(ttl, maximumSize, Clock.systemUTC());
  }

  NotificationCooldownCache(Duration ttl, int maximumSize, Clock clock) {
    this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    if (maximumSize <= 0) {
      throw new IllegalArgumentException("maximumSize must be positive");
    }
    this.maximumSize = maximumSize;
  }

  /**
   * Checks whether the supplied key is still on cooldown.
   *
   * <p>If the entry has already expired according to the configured {@link #clock}, it is removed
   * as part of this lookup.
   *
   * @param key cache key to inspect
   * @return {@code true} if the key exists and has not yet expired; {@code false} otherwise
   */
  public boolean isOnCooldown(String key) {
    Instant expiresAt = entries.get(key);
    if (expiresAt == null) {
      return false;
    }
    if (expiresAt.isAfter(clock.instant())) {
      return true;
    }
    entries.remove(key, expiresAt);
    return false;
  }

  /**
   * Attempts to acquire cooldown ownership for the supplied key atomically.
   *
   * <p>If the key is absent or expired, a new cooldown entry is stored and {@code true} is
   * returned. The maximum size limit is enforced on a best-effort basis under concurrency.
   *
   * @param key cache key to acquire
   * @return {@code true} when the caller acquired the cooldown entry; {@code false} when an active
   *     cooldown already exists
   */
  public synchronized boolean tryAcquire(String key) {
    pruneExpiredEntries();
    if (entries.size() >= maximumSize) {
      removeOneEntry();
    }
    Instant expiresAt = clock.instant().plus(ttl);
    Instant existing = entries.putIfAbsent(key, expiresAt);
    if (existing == null) {
      return true;
    }
    if (existing.isAfter(clock.instant())) {
      return false;
    }
    entries.put(key, expiresAt);
    return true;
  }

  /**
   * Removes a cooldown entry explicitly, typically when a delivery attempt failed before handoff.
   *
   * @param key cache key to remove
   */
  public void release(String key) {
    entries.remove(key);
  }

  private void pruneExpiredEntries() {
    Instant now = clock.instant();
    entries.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
  }

  private void removeOneEntry() {
    Iterator<String> iterator = entries.keySet().iterator();
    if (iterator.hasNext()) {
      entries.remove(iterator.next());
    }
  }
}
