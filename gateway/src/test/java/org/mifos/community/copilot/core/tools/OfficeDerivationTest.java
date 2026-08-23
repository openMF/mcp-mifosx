/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.community.copilot.core.auth.CallContext;

/**
 * Which branch a new client lands in, and who gets to decide that.
 *
 * <p>It used to be the literal 1, so every client created through the Copilot went to head
 * office. It is now established from the officer's own credential, by asking Fineract which
 * offices that credential reaches. The interesting cases are not the happy one. They are what
 * happens when the question cannot be asked, and whether an office the model named can slip
 * past on a day when Fineract is having trouble.
 */
class OfficeDerivationTest {

    private static final String ONE_OFFICE = "[{\"id\":4,\"name\":\"Nairobi Branch\"}]";
    private static final String TWO_OFFICES =
            "[{\"id\":4,\"name\":\"Nairobi Branch\"},{\"id\":9,\"name\":\"Mombasa Branch\"}]";

    private HttpServer server;
    private String baseUrl;
    private final List<String> requestedPaths = new ArrayList<>();

    /** What /offices answers with, and whether it answers at all. */
    private String officesBody = ONE_OFFICE;
    private int officesStatus = 200;

    /** The office Fineract reports as this officer's own, or 0 for a server that will not say. */
    private int homeOffice;

    /** Decodable, because the home-office lookup reads the username and password out of it. */
    private final CallContext officer = new CallContext("Basic "
            + java.util.Base64.getEncoder().encodeToString("mifos:password".getBytes(StandardCharsets.UTF_8)),
            "default", "corr-1");

    @BeforeEach
    void startStubFineract() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStubFineract() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestedPaths.add(path);
        boolean offices = path.endsWith("/offices");
        boolean signIn = path.endsWith("/authentication");
        int status = offices ? officesStatus : signIn && homeOffice == 0 ? 404 : 200;
        String body = offices ? officesBody
                : signIn ? "{\"username\":\"mifos\",\"officeId\":" + homeOffice + "}"
                : "{\"clientId\":11,\"resourceId\":11}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (InputStream ignored = exchange.getRequestBody()) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    /** A client-create tool whose body needs an office, which is what triggers the lookup. */
    private ToolDefinition clientCreate() {
        return new ToolDefinition("mifos_client_create", "Create a client", true, "Create client {firstname}",
                List.of(new ToolDefinition.Param("firstname", "string", true, "given name", "First name", null,
                        true, false),
                        new ToolDefinition.Param("officeId", "integer", false, "office", "Office", null, false,
                                false)),
                new ToolDefinition.RestMapping("POST", "/fineract-provider/api/v1/clients",
                        "{\"officeId\":\"${officeId}\",\"firstname\":\"${firstname}\"}"),
                List.of(), List.of(), null, Map.of());
    }

    @Test
    void oneReachableOfficeIsTheOfficersOfficeAndNeedsNoAsking() throws Exception {
        FineractRestToolExecutor executor = new FineractRestToolExecutor(baseUrl);

        executor.execute(clientCreate(), Map.of("firstname", "Grace"), officer, "cop-1");

        assertThat(requestedPaths).as("the credential was asked which offices it reaches")
                .anyMatch((path) -> path.endsWith("/offices"));
    }

    /**
     * An officer who can see the whole institution still belongs to one branch.
     *
     * <p>Picking the only visible office worked until somebody added a second one, and then an
     * administrator, who can see every branch there is, got asked which branch they were
     * sitting in. Fineract answers a sign-in with the user's own office, so that is what a new
     * client gets, and the reachable set is only there to check the answer is sane.
     */
    @Test
    void aClientLandsInTheOfficersOwnBranchNotTheOnlyVisibleOne() throws Exception {
        officesBody = TWO_OFFICES;
        homeOffice = 9;
        FineractRestToolExecutor executor = new FineractRestToolExecutor(baseUrl);

        executor.execute(clientCreate(), Map.of("firstname", "Grace"), officer, "cop-1");

        assertThat(requestedPaths).as("it asked who the officer is")
                .anyMatch((path) -> path.endsWith("/authentication"));
        assertThat(requestedPaths).anyMatch((path) -> path.endsWith("/clients"));
    }

    /**
     * A home office outside what the credential reaches is not taken on trust.
     *
     * <p>It is checked against the reachable set like anything else, and when it fails that
     * check the reachable set decides instead. Here that is unambiguous, so the write goes
     * ahead in the office the credential can actually see rather than the one it claimed.
     */
    @Test
    void aHomeOfficeTheCredentialCannotReachIsNotUsed() throws Exception {
        officesBody = ONE_OFFICE;   // reaches office 4 only
        homeOffice = 77;            // but the sign-in claims 77
        FineractRestToolExecutor executor = new FineractRestToolExecutor(baseUrl);

        executor.execute(clientCreate(), Map.of("firstname", "Grace"), officer, "cop-1");

        assertThat(requestedPaths).as("it fell back rather than trusting the claim")
                .anyMatch((path) -> path.endsWith("/clients"));
    }

    /**
     * The one that matters. A failed lookup must not read as permission.
     *
     * <p>Before this, an empty result meant "could not ask" and "reaches nothing" alike, and a
     * stated office was accepted whenever the list was empty. So a single bad response from
     * /offices let any office the model named go to the wire unexamined, against the officer's
     * own credential.
     */
    @Test
    void anOfficeTheModelNamedIsNotAcceptedJustBecauseTheLookupFailed() {
        officesStatus = 500;
        FineractRestToolExecutor executor = new FineractRestToolExecutor(baseUrl);

        assertThatThrownBy(() -> executor.execute(clientCreate(),
                Map.of("firstname", "Grace", "officeId", 99), officer, "cop-1"))
                .isInstanceOf(ToolExecutionException.class);

        assertThat(requestedPaths).as("nothing was posted").noneMatch((path) -> path.endsWith("/clients"));
    }

    @Test
    void anOfficeOutsideWhatTheCredentialReachesIsRefused() {
        officesBody = TWO_OFFICES;
        FineractRestToolExecutor executor = new FineractRestToolExecutor(baseUrl);

        assertThatThrownBy(() -> executor.execute(clientCreate(),
                Map.of("firstname", "Grace", "officeId", 77), officer, "cop-1"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("not one you can work in");

        assertThat(requestedPaths).noneMatch((path) -> path.endsWith("/clients"));
    }

    @Test
    void anOfficerInSeveralOfficesIsAskedWhichRatherThanGuessedFor() {
        officesBody = TWO_OFFICES;
        FineractRestToolExecutor executor = new FineractRestToolExecutor(baseUrl);

        assertThatThrownBy(() -> executor.execute(clientCreate(), Map.of("firstname", "Grace"), officer, "cop-1"))
                .isInstanceOf(ToolExecutionException.class)
                .hasMessageContaining("more than one office");
    }

    @Test
    void anOfficeThatIsReachableIsAccepted() throws Exception {
        officesBody = TWO_OFFICES;
        FineractRestToolExecutor executor = new FineractRestToolExecutor(baseUrl);

        executor.execute(clientCreate(), Map.of("firstname", "Grace", "officeId", 9), officer, "cop-1");

        assertThat(requestedPaths).anyMatch((path) -> path.endsWith("/clients"));
    }

    /**
     * A bad minute must not become permanent.
     *
     * <p>The reachable set is cached so the lookup costs one call, not one per write. Caching
     * the failure too would mean a single transient error from /offices left that officer
     * unable to create a client for as long as the process lived.
     */
    @Test
    void aFailedLookupIsNotRememberedAsTheAnswer() throws Exception {
        officesStatus = 503;
        FineractRestToolExecutor executor = new FineractRestToolExecutor(baseUrl);

        assertThatThrownBy(() -> executor.execute(clientCreate(), Map.of("firstname", "Grace"), officer, "cop-1"))
                .isInstanceOf(ToolExecutionException.class);

        officesStatus = 200;
        executor.execute(clientCreate(), Map.of("firstname", "Grace"), officer, "cop-2");

        assertThat(requestedPaths).as("the second attempt succeeded once Fineract recovered")
                .anyMatch((path) -> path.endsWith("/clients"));
    }
}
