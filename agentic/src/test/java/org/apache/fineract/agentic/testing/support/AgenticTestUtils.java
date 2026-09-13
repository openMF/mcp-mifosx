/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.agentic.testing.support;

import static io.restassured.RestAssured.given;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Shared HTTP and authentication helpers for all integration tests.
 *
 * <p>Mirrors the role of Apache Fineract's {@code Utils.java} in the integration-tests module,
 * providing consistent request specifications and auth header construction.
 */
public final class AgenticTestUtils {

  private AgenticTestUtils() {}

  public static final String CONTEXT_PATH = "/fineract-provider";

  /** API path constants used across integration test classes. */
  public static final String SELF_AUTH_PATH = CONTEXT_PATH + "/api/v1/self/authentication";

  public static final String SELF_REGISTRATION_PATH = CONTEXT_PATH + "/api/v1/self/registration";
  public static final String SELF_PASSWORD_REQUEST_PATH =
      CONTEXT_PATH + "/api/v1/self/password/request";
  public static final String SELF_PASSWORD_RENEW_PATH =
      CONTEXT_PATH + "/api/v1/self/password/renew";
  public static final String SELF_CLIENTS_PATH = CONTEXT_PATH + "/api/v1/self/clients";
  public static final String SELF_SAVINGS_PATH = CONTEXT_PATH + "/api/v1/self/savingsaccounts";
  public static final String SELF_LOANS_PATH = CONTEXT_PATH + "/api/v1/self/loans";
  public static final String SELF_LOAN_PRODUCTS_PATH = CONTEXT_PATH + "/api/v1/self/loanproducts";
  public static final String SELF_SAVINGS_PRODUCTS_PATH =
      CONTEXT_PATH + "/api/v1/self/savingsproducts";

  public static final String SELF_LOAN_SIMULATION_PATH =
      CONTEXT_PATH + "/api/v1/self/loans/simulate";
  public static final String SELF_LOAN_SIMULATION_PRODUCTS_PATH =
      SELF_LOAN_SIMULATION_PATH + "/products";
  public static final String SELF_LOAN_SIMULATION_TEMPLATE_PATH =
      SELF_LOAN_SIMULATION_PATH + "/template";

  /** Tenant identifier expected by the self-service security filter. */
  public static final String DEFAULT_TENANT = "default";

  /** Builds a base-64 Basic Auth header value from username and password. */
  public static String basicAuthHeader(String username, String password) {
    String raw = username + ":" + password;
    return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Returns a pre-configured {@link RequestSpecification} with JSON content type and the default
   * Fineract tenant header, pointed at the given local server port.
   */
  public static RequestSpecification requestSpec(int port) {
    return given()
        .relaxedHTTPSValidation()
        .baseUri("https://localhost")
        .port(port)
        .contentType(ContentType.JSON)
        .accept(ContentType.JSON)
        .header("Fineract-Platform-TenantId", DEFAULT_TENANT);
  }

  /**
   * Returns a request specification that includes a Basic Auth header for the given credentials.
   * Use this to test authenticated endpoints.
   */
  public static RequestSpecification requestSpecWithAuth(
      int port, String username, String password) {
    return requestSpec(port).header("Authorization", basicAuthHeader(username, password));
  }

  /**
   * POSTs credentials to the self-service authentication endpoint and returns the full response.
   * Callers can assert the status code or extract the {@code base64EncodedAuthenticationKey}.
   */
  public static Response authenticate(int port, String username, String password) {
    String body = String.format("{\"username\":\"%s\",\"password\":\"%s\"}", username, password);
    return given(requestSpec(port)).body(body).when().post(SELF_AUTH_PATH);
  }
}
