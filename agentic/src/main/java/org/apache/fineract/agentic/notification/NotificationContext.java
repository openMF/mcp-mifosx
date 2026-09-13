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

/**
 * Thread-local holder for the current notification event type.
 *
 * <p>This context is used to carry the event type name across asynchronous boundaries (e.g., into
 * {@code @Async} notification handlers). Because the underlying storage is a {@link ThreadLocal},
 * callers <strong>must</strong> ensure that the context is cleared after use to prevent memory
 * leaks in thread-pool environments.
 *
 * <h3>Recommended usage</h3>
 *
 * Use the {@link #bind(String)} helper, which returns an {@link AutoCloseable} scope that
 * automatically calls {@link #clear()} on close:
 *
 * <pre>{@code
 * try (NotificationContext.Scope ignored = NotificationContext.bind("LOGIN_SUCCESS")) {
 *     applicationEventPublisher.publishEvent(event);
 * }
 * // CURRENT_EVENT is guaranteed to be cleared here
 * }</pre>
 *
 * <p>If you use {@link #set(String)} directly, you <strong>must</strong> call {@link #clear()} in a
 * {@code finally} block:
 *
 * <pre>{@code
 * NotificationContext.set("LOGIN_SUCCESS");
 * try {
 *     applicationEventPublisher.publishEvent(event);
 * } finally {
 *     NotificationContext.clear();
 * }
 * }</pre>
 *
 * @see #bind(String)
 * @see #clear()
 */
public class NotificationContext {
  private static final ThreadLocal<String> CURRENT_EVENT = new ThreadLocal<>();

  public static void set(String eventType) {
    CURRENT_EVENT.set(eventType);
  }

  public static String get() {
    return CURRENT_EVENT.get() != null ? CURRENT_EVENT.get() : "UNKNOWN";
  }

  /**
   * Removes the current event type from thread-local storage.
   *
   * <p>This method <strong>must</strong> be called after every {@link #set(String)} to prevent
   * thread-local leaks when threads are returned to a pool. Prefer using {@link #bind(String)}
   * which handles cleanup automatically.
   *
   * @see #bind(String)
   */
  public static void clear() {
    CURRENT_EVENT.remove();
  }

  /**
   * Sets the current event type and returns an {@link AutoCloseable} scope that calls {@link
   * #clear()} when closed. This is the preferred way to use {@code NotificationContext} as it
   * guarantees cleanup even if an exception is thrown.
   *
   * <pre>{@code
   * try (NotificationContext.Scope ignored = NotificationContext.bind("LOGIN_SUCCESS")) {
   *     // publish event
   * }
   * }</pre>
   *
   * @param eventType the event type name to bind (e.g., {@code "LOGIN_SUCCESS"})
   * @return a {@link Scope} that will call {@link #clear()} on {@link Scope#close()}
   */
  public static Scope bind(String eventType) {
    String previous = CURRENT_EVENT.get();
    set(eventType);
    return new Scope(previous);
  }

  /**
   * An {@link AutoCloseable} handle that clears the {@link NotificationContext} when closed.
   * Instances are obtained via {@link NotificationContext#bind(String)}.
   */
  public static final class Scope implements AutoCloseable {
    private final String previous;

    private Scope(String previous) {
      this.previous = previous;
    }

    @Override
    public void close() {
      if (previous == null) {
        NotificationContext.clear();
      } else {
        NotificationContext.set(previous);
      }
    }
  }
}
