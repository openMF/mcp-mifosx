/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.mifos.community.ai.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.json.Json;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.mifos.community.ai.mcp.SearchParameters;
import org.mifos.community.ai.mcp.dto.Address;
import org.mifos.community.ai.mcp.dto.Client;
import org.mifos.community.ai.mcp.dto.FamilyMember;

@RegisterRestClient(configKey = "mifosx")
@ClientHeaderParam(name = "Authorization", value = "{getAuthorizationHeader}")
@ClientHeaderParam(name = "fineract-platform-tenantid", value = "{getTenantHeader}")
public interface MifosXClient {
    
    final Config config = ConfigProvider.getConfig();
    
    default String getAuthorizationHeader() {      
      final String apiKey = config.getConfigValue("mifos.basic.token").getValue();
      return "Basic " + apiKey;
    }
    
    default String getTenantHeader() {
      final String tenant = config.getConfigValue("mifos.tenantid").getValue();
      return tenant;
    }

    @GET
    @Path("/fineract-provider/api/v1/search")
    JsonNode getClientByAccount(@BeanParam SearchParameters filterParams);
        
    @GET
    @Path("/fineract-provider/api/v1/clients/{clientId}")
    JsonNode getClientDetailsById(Integer clientId);
    
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/fineract-provider/api/v1/clients")
    JsonNode createClient(String client);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/fineract-provider/api/v1/clients/{clientId}")
    JsonNode activateClient(@PathParam("clientId") Integer clientId,
                            @QueryParam("command") String command,
                            String activateClient);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/fineract-provider/api/v2/clients/search")
    JsonNode listClients(String searchText);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/fineract-provider/api/v1/client/{clientId}/addresses")
    JsonNode addAddress(@PathParam("clientId") Integer clientId,
                        @QueryParam("type") Integer type,
                        String address);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/fineract-provider/api/v1/clients/{clientId}/familymembers")
    JsonNode addFamilyMember(Integer clientId, String familyMember);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/fineract-provider/api/v1/savingsproducts")
    JsonNode createDefaultSavingsProduct(String defaultSavingsProduct);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/fineract-provider/api/v1/savingsaccounts/template")
    JsonNode getTemplateSavingsAccount(@QueryParam("clientId") Integer clientId,
                                       @QueryParam("productId") Integer productId);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/fineract-provider/api/v1/savingsaccounts")
    JsonNode newSavingAccountApplication(String newSavingAccountApplication);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/fineract-provider/api/v1/loans")
    JsonNode newLoanAccountApplication(String newLoanAccountApplication);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/fineract-provider/api/v1/savingsaccounts/{accountNumber}")
    JsonNode approveSavingsAccount(@PathParam("accountNumber") Integer accountNumber,
                                   @QueryParam("command") String command,
                                   String approveSavingsAccount);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/fineract-provider/api/v1/savingsaccounts/{accountNumber}")
    JsonNode activateSavingsAccount(@PathParam("accountNumber") Integer accountNumber,
                                   @QueryParam("command") String command,
                                   String activateSavingsAccount);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("fineract-provider/api/v1/savingsaccounts/{clientId}/transactions/template")
    JsonNode getSavingsTransactionTemplate(@PathParam("clientId") Integer clientId);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("fineract-provider/api/v1/savingsaccounts/{clientId}/transactions")
    JsonNode newSavingsTransaction(@PathParam("clientId") Integer clientId,
                                   @QueryParam("command") String command,
                                   String SavingsTransaction);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("fineract-provider/api/v1/loanproducts/template")
    JsonNode getLoanProductTemplate();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("fineract-provider/api/v1/loanproducts")
    JsonNode createDefaultLoanProduct(String defaultLoanProduct);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("fineract-provider/api/v1/currencies")
    JsonNode getCurrencies();

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("fineract-provider/api/v1/codes")
    JsonNode getCodes();

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("fineract-provider/api/v1/codes/{codeId}")
    JsonNode getCodeById(Integer codeId);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("fineract-provider/api/v1/codes/{codeId}/codevalues")
    JsonNode getCodeValues(Integer codeId);
}