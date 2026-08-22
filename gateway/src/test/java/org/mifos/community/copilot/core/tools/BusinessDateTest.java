/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.community.copilot.core.auth.CallContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which day a command is dated with.
 *
 * <p>This is not cosmetic. Fineract rejects future-dated commands, so a gateway running an
 * hour ahead of the tenant fails every write after the rollover. The order of preference is
 * the tenant's configured business date, then the calendar day in the core banking server's
 * own Date header, then this host's clock as a last resort.
 *
 * <p>Served over a raw socket rather than {@code com.sun.net.httpserver}, which stamps its
 * own Date header and would make the middle case untestable.
 */
class BusinessDateTest {

    /** A day that is deliberately not today, so a host-clock answer cannot pass by accident. */
    private static final String SERVER_DAY = "Fri, 21 Aug 2026 19:27:50 GMT";
    private static final String SERVER_DAY_ISO = "2026-08-21";

    private ServerSocket socket;
    private Thread acceptor;
    private String baseUrl;
    private final CallContext officer = new CallContext("Basic abc", "default", "corr-1");

    private volatile int status = 200;
    private volatile String body = "{\"date\":[2026,8,21]}";
    private volatile String dateHeader = SERVER_DAY;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startStub() throws IOException {
        socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress());
        baseUrl = "http://127.0.0.1:" + socket.getLocalPort();
        acceptor = new Thread(this::serve, "stub-fineract");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stopStub() throws IOException {
        socket.close();
        acceptor.interrupt();
    }

    private void serve() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                hits.incrementAndGet();
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                    // Drain the request; the stub answers the same way whatever was asked.
                }
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                StringBuilder head = new StringBuilder("HTTP/1.1 ").append(status).append(" X\r\n")
                        .append("Content-Type: application/json\r\n")
                        .append("Content-Length: ").append(payload.length).append("\r\n")
                        .append("Connection: close\r\n");
                if (dateHeader != null) {
                    head.append("Date: ").append(dateHeader).append("\r\n");
                }
                head.append("\r\n");
                OutputStream out = client.getOutputStream();
                out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
                out.write(payload);
                out.flush();
            } catch (IOException e) {
                return; // Socket closed at teardown.
            }
        }
    }

    private String resolve() {
        return new FineractRestToolExecutor(baseUrl).businessDate(officer);
    }

    @Test
    void theTenantsConfiguredBusinessDateWins() {
        assertThat(resolve()).isEqualTo(SERVER_DAY_ISO);
    }

    @Test
    void anIsoStringIsAcceptedToo() {
        body = "{\"date\":\"2026-08-21\"}";

        assertThat(resolve()).isEqualTo(SERVER_DAY_ISO);
    }

    @Test
    void withoutTheBusinessDateModuleTheServersOwnCalendarDayIsUsed() {
        // The endpoint 404s on deployments that do not run the module, but the response still
        // tells us what day the server thinks it is.
        status = 404;
        body = "{\"developerMessage\":\"not found\"}";

        assertThat(resolve()).isEqualTo(SERVER_DAY_ISO);
    }

    @Test
    void anUnreadableBodyStillFallsBackToTheServersCalendarDay() {
        // A proxy answering 200 with an HTML error page must not cost us the Date header:
        // dropping to this host's clock is how future-dated commands start getting rejected.
        body = "<html><body>Gateway Timeout</body></html>";

        assertThat(resolve()).isEqualTo(SERVER_DAY_ISO);
    }

    @Test
    void anImpossibleDateInTheBodyDoesNotCostUsTheHeaderEither() {
        body = "{\"date\":[2026,13,45]}";

        assertThat(resolve()).isEqualTo(SERVER_DAY_ISO);
    }

    @Test
    void withNothingUsableAnywhereTheHostClockIsTheLastResort() {
        status = 500;
        body = "nope";
        dateHeader = null;

        // Bracket the call rather than comparing to a single LocalDate.now(): a run that
        // straddles midnight would otherwise fail for no reason.
        LocalDate before = LocalDate.now();
        LocalDate resolved = LocalDate.parse(resolve());
        LocalDate after = LocalDate.now();

        assertThat(resolved).isBetween(before, after);
    }

    @Test
    void theLookupIsCachedSoEveryToolCallDoesNotPayForIt() {
        FineractRestToolExecutor executor = new FineractRestToolExecutor(baseUrl);
        executor.businessDate(officer);
        int afterFirst = hits.get();
        executor.businessDate(officer);
        executor.businessDate(officer);

        assertThat(hits.get()).isEqualTo(afterFirst);
    }

    @Test
    void eachTenantGetsItsOwnCalendar() {
        // Fineract is multi-tenant and business dates differ per tenant; one shared entry
        // would serve one tenant's calendar to another.
        FineractRestToolExecutor executor = new FineractRestToolExecutor(baseUrl);
        assertThat(executor.businessDate(officer)).isEqualTo(SERVER_DAY_ISO);
        body = "{\"date\":[2026,8,19]}";

        assertThat(executor.businessDate(new CallContext("Basic abc", "other-tenant", "corr-2")))
                .isEqualTo("2026-08-19");
        assertThat(executor.businessDate(officer)).isEqualTo(SERVER_DAY_ISO);
    }
}
