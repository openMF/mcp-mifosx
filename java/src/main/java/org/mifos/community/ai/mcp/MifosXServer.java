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
package org.mifos.community.ai.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

import org.mifos.community.ai.mcp.client.MifosXClient;
import org.mifos.community.ai.mcp.dto.*;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

public class MifosXServer {

    @RestClient
    MifosXClient mifosXClient;
    
    @Inject
    Validator validator;
    @Inject
    ObjectMapper mapper;

    private static final Logger log = Logger.getLogger(MifosXServer.class);

    @Tool(description = "Search for a client account by account number or client full name")
    JsonNode getClientByAccount(@ToolArg(description = "Client account number (e.g. 00000001)") String clientAccountNumber) {
        SearchParameters searchParameters = new SearchParameters();
        searchParameters.query=clientAccountNumber;
        return mifosXClient.getClientByAccount(searchParameters);
    }
    
    @Tool(description = "Get client by id")
    JsonNode getClientDetailsById(@ToolArg(description = "Client Id (e.g. 1)") Integer clientId) {        
        return mifosXClient.getClientDetailsById(clientId);
    }

    @Tool(description = "List out " +
            "clients")
    JsonNode listClients(@ToolArg(description = "Optional search text (e.g. John)", required = false) String searchText) throws JsonProcessingException{

        Request request = new Request();
        request.setText(searchText != null ? searchText : "");

        ClientSearch clientSearch = new ClientSearch();
        clientSearch.setRequest(request);
        clientSearch.setPage(0);
        clientSearch.setSize(50);
        ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
        String jsonClientSearch = ow.writeValueAsString(clientSearch);
        return mifosXClient.listClients(jsonClientSearch);
    }
       
    @Tool(description = "Create a client using first name, last name, email address, mobile number and external id")
    JsonNode createClient(@ToolArg(description = "First Name (e.g. Jhon)", required = true) String firstName, 
            @ToolArg(description = "Last Name (e.g. Doe)", required = true) String lastName,
            @ToolArg(description = "Optional Email Address (e.g. jhon@gmail.com)", required = false) String emailAddress,
            @ToolArg(description = "Optional Mobile Number (e.g. +5215522649494)", required = false) String mobileNo,
            @ToolArg(description = "Optional External Id (e.g. VR12)", required = false) String externalId) throws JsonProcessingException {
        Client client = new Client();
        client.setFirstname(firstName);
        client.setLastname(lastName);
        if(emailAddress != null){
            client.setEmailAddress(emailAddress);
        }
        if(mobileNo != null){
            client.setMobileNo(mobileNo);
        }
        if(mobileNo != null){
            client.setExternalId(externalId);
        }                
        client.setOfficeId(1);
        client.setLegalFormId(1);
        client.setIsStaff("false");
        client.setActive(false);        
        client.setDateFormat("yyyy-MM-dd");
        client.setLocale("en");
        LocalDate currentDate = LocalDate.now();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(client.getDateFormat());
        String formattedDate = currentDate.format(dtf);
        client.setActivationDate(formattedDate);
        client.setSubmittedOnDate(formattedDate);
        ArrayList<FamilyMember> familyMembers = new ArrayList<FamilyMember>();
        client.setFamilyMembers(familyMembers);
        ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
        String jsonClient = ow.writeValueAsString(client);
        return mifosXClient.createClient(jsonClient);
    }

    @Tool(description = "Activate a client using his account number. " +
            "Optionally provide an activation date. If omitted, today's date will be used.")
    JsonNode activateClient(@ToolArg(description = "Client Id (e.g. 1)") Integer clientId,
                            @ToolArg(description = "Activation Date (e.g. 22 April 2025)") String activationDate)
            throws JsonProcessingException {
        ClientActivation clientActivation = new ClientActivation();

        if (activationDate != null)
        {
            clientActivation.setActivationDate(activationDate);
        }
        else {
            LocalDate currentDate = LocalDate.now();
            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd MMMM yyyy");
            String formattedDate = currentDate.format(dtf);
            clientActivation.setActivationDate(formattedDate);
        }
        clientActivation.setDateFormat("dd MMMM yyyy");
        clientActivation.setLocale("en");

        ObjectMapper ow = new ObjectMapper();
        String jsonActiveClient = ow.writeValueAsString(clientActivation);
        return mifosXClient.activateClient(clientId, "activate",jsonActiveClient);
    }

    @Tool(description = "Add an address to a client by his account number. Required fields: address type, address, neighborhood, number, " +
            "city, country, postal code, state province")
    JsonNode addAddress(@ToolArg(description = "Client Id (e.g. 1)") Integer clientId,
                        @ToolArg(description = "Address Type (e.g Home)") String addressType,
                        @ToolArg(description = "Address Line 1 (e.g. 742 Evergreen Terrace)") String adrress,
                        @ToolArg(description = "Address Line 2 (optional, e.g. Apt 2B)", required = false) String neighborhood,
                        @ToolArg(description = "Address Line 3 (optional, e.g. Floor 3)", required = false) String number,
                        @ToolArg(description = "City (e.g. Springfield)") String city,
                        @ToolArg(description = "State/Province (e.g. México)", required = false) String stateProvince,
                        @ToolArg(description = "Country (e.g. USA)", required = false) String country,
                        @ToolArg(description = "Postal Code (e.g. 12345)") String postalCode) throws JsonProcessingException {
        Address address = new Address();

        address.setAddressType(getCodeValueId(address.getAddressTypeCodeValueId(), addressType));
        address.setAddressLine1(adrress);
        address.setAddressLine2(Optional.ofNullable(neighborhood).orElse(""));
        address.setAddressLine3(Optional.ofNullable(number).orElse(""));
        address.setCity(city);
        address.setStateProvinceId(getCodeValueId(address.getStateProvinceCodeValueId(), stateProvince));
        address.setCountryId(getCodeValueId(address.getCountryCodeValueId(), country));
        address.setPostalCode(postalCode);

        ObjectMapper ow = new ObjectMapper();
        //ow.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        Set<ConstraintViolation<Address>> violations = validator.validate(address);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }

        String jsonAddress = ow.writeValueAsString(address);
        jsonAddress = jsonAddress.replace(":null", ":\"\"");
        log.info("jsonAddress: " + jsonAddress);
        return mifosXClient.addAddress(clientId,address.getAddressType(),jsonAddress);
    }

    @Tool(description = "Add a family member to a client by his account number. Required fields: firstName, lastName, age, relationship, genderId, dateOfBirth," +
            " middleName, qualification, isDependent, professionId, maritalStatusId, dateFormat, locale")
    JsonNode addFamilyMember(@ToolArg(description = "Client Id (e.g. 1)") Integer clientId,
            @ToolArg(description = "First Name (e.g. Jhon)") String firstName,
            @ToolArg(description = "Middle Name (e.g. Cena), replace with \"\" if not provided", required = false) String middleName,
            @ToolArg(description = "Last Name (e.g. Doe)") String lastName,
            @ToolArg(description = "Qualification (e.g. MBA), replace with \"\" if not provided", required = false) String qualification,
            @ToolArg(description = "Age (e.g. 25)") Integer age,
            @ToolArg(description = "Is Dependent (e.g. Dependent), replace with \"\" if not provided", required = false) String isDependent,
            @ToolArg(description = "Relationship (e.g. friend)") String relationship,
            @ToolArg(description = "Gender (e.g. male)") String gender,
            @ToolArg(description = "Profession (e.g. unemployed), replace with \"\" if not provided", required = false) String profession,
            @ToolArg(description = "Marital Status (e.g. married)", required = false) String maritalStatus,
            @ToolArg(description = "Date of Birth (e.g. 03 June 2003)") String dateOfBirth,
            @ToolArg(description = "Date Format (e.g. dd MMMM yyyy)",required = false) String dateFormat,
            @ToolArg(description = "Locale (e.g. en)",required = false) String locale) throws JsonProcessingException {
        FamilyMember familyMember = new FamilyMember();

        familyMember.setIsDependent(isDependent.equalsIgnoreCase("dependent") ||
                isDependent.equalsIgnoreCase("is dependent") ? "true" : "false");

        familyMember.setRelationshipId(Optional.ofNullable(getCodeValueId(familyMember
                .getRelationshipCodeValueId(), relationship)).orElse(familyMember.getDefaultRelationshipId()));
        familyMember.setGenderId(Optional.ofNullable(getCodeValueId(familyMember
                .getGenderCodeValueId(), gender)).orElse(familyMember.getDefaultGenderId()));

        familyMember.setProfessionId(getCodeValueId(familyMember.getProfessionCodeValueId(), profession));
        familyMember.setMaritalStatusId(getCodeValueId(familyMember.getMaritalStatusCodeValueId(), maritalStatus));

        familyMember.setFirstName(firstName);
        familyMember.setMiddleName(Optional.ofNullable(middleName).orElse(""));
        familyMember.setLastName(lastName);
        familyMember.setQualification(Optional.ofNullable(qualification).orElse(""));
        familyMember.setAge(age);
        familyMember.setDateOfBirth(dateOfBirth);
        familyMember.setDateFormat("dd MMMM yyyy");

        familyMember.setLocale("en");

        ObjectMapper ow = new ObjectMapper();
        //ow.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        String jsonClient = ow.writeValueAsString(familyMember);
        jsonClient = jsonClient.replace(":null", ":\"\"");

        return mifosXClient.addFamilyMember(clientId, jsonClient);
    }

    @Tool(description = "Create a default savings product. " +
            "Provide only the following inputs: name, short name, description, and currency. " +
            "All other values will be automatically set with default configuration. " +
            "Use this to quickly initialize standard savings products.")
    JsonNode createDefaultSavingProduct(@ToolArg(description = "Saving product name (e.g. WALLET)") String name,
        @ToolArg(description = "Short name of the savings product (e.g. WL01)") String shortName,
        @ToolArg(description = "Short description of the savings product (e.g. WALLET PRODUCT)") String description,
        @ToolArg(description = "Currency for the savings product (e.g. USD)") String currency) throws JsonProcessingException{
        SavingProduct savingProduct = new SavingProduct();

        savingProduct.setName(name);
        savingProduct.setShortName(shortName);
        savingProduct.setDescription(description);
        savingProduct.setCurrencyCode(getCurrencyCode(currency));
        savingProduct.setDigitsAfterDecimal(2);
        savingProduct.setInMultiplesOf(null);
        savingProduct.setNominalAnnualInterestRate(0);
        savingProduct.setInterestCompoundingPeriodType(1);
        savingProduct.setInterestPostingPeriodType(4);
        savingProduct.setInterestCalculationType(1);
        savingProduct.setInterestCalculationDaysInYearType(365);
        savingProduct.setWithdrawalFeeForTransfers("false");
        savingProduct.setEnforceMinRequiredBalance("false");
        savingProduct.setAllowOverdraft("false");
        savingProduct.setWithHoldTax("false");
        savingProduct.setIsDormancyTrackingActive("false");

        ArrayList<Charge> charges = new ArrayList<>();
        savingProduct.setCharges(charges);

        savingProduct.setAccountingRule(1);
        savingProduct.setLocale("en");

        ObjectMapper ow = new ObjectMapper();

        Set<ConstraintViolation<SavingProduct>> violations = validator.validate(savingProduct);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }

        String jsonClient = ow.writeValueAsString(savingProduct);
        jsonClient = jsonClient.replace(":null", ":\"\"");

        return mifosXClient.createDefaultSavingsProduct(jsonClient);
    }

    @Tool(description = "Create a new loan account for a client using their account number and a loan product ID. " +
            "The following fields are required: loanType, expectedDisbursementDate, interestRateFrequencyType, " +
            "interestRatePerPeriod, isEqualAmortization, numberOfRepayments, principal, repaymentEvery, " +
            "repaymentFrequencyType, and submittedOnDate.")
    JsonNode newLoanAccountApplication(@ToolArg(description = "Client Id (e.g. 1)") Integer clientId,
           @ToolArg(description = "Loan Type (e.g. Individual)") String loanType,
           @ToolArg(description = "Expected Disbursement Date (e.g 14 April 2025)") String expectedDisbursementDate,
           @ToolArg(description = "Interest Rate Frequency Type (e.g 2)" ) Integer interestRateFrequencyType,
           @ToolArg(description = "Interest Rate Per Period (e.g 5)") BigDecimal interestRatePerPeriod,
           @ToolArg(description = "Is Equal Amortization (e.g \"false\")") String isEqualAmortization,
           @ToolArg(description = "Number Of Repayments (e.g 2)") Integer numberOfRepayments,
           @ToolArg(description = "Principal (e.g 1000)") BigDecimal principal,
           @ToolArg(description = "Product Id (e.g 2)") Integer productId,
           @ToolArg(description = "Repayment Every (e.g 2)") Integer repaymentEvery,
           @ToolArg(description = "Repayment Frequency Type (e.g 2)") Integer repaymentFrequencyType,
           @ToolArg(description = "Submitted on Date (e.g 14 April 2025)") String submittedOnDate
    ) throws JsonProcessingException {
        LoanProductApplication loanProductApplication = new LoanProductApplication();

        loanProductApplication.setAllowPartialPeriodInterestCalcualtion("false");
        loanProductApplication.setAmortizationType(1);
        ArrayList<Object> charges = new ArrayList<>();
        loanProductApplication.setCharges(charges);
        loanProductApplication.setClientId(clientId);
        ArrayList<Object> colateral = new ArrayList<>();
        loanProductApplication.setCollateral(colateral);
        loanProductApplication.setCreateStandingInstructionAtDisbursement("");
        loanProductApplication.setDateFormat("dd MMMM yyyy");
        loanProductApplication.setExpectedDisbursementDate(expectedDisbursementDate);
        loanProductApplication.setExternalId(null);
        loanProductApplication.setFundId(null);
        loanProductApplication.setInterestCalculationPeriodType(1);
        loanProductApplication.setInterestChargedFromDate(null);
        loanProductApplication.setInterestRateFrequencyType(interestRateFrequencyType);
        loanProductApplication.setInterestRatePerPeriod(interestRatePerPeriod);
        loanProductApplication.setInterestType(0);
        loanProductApplication.setIsEqualAmortization(isEqualAmortization);
        loanProductApplication.setIsTopup("");
        loanProductApplication.setLinkAccountId("");
        loanProductApplication.setLoanIdToClose("");
        loanProductApplication.setLoanOfficerId("");
        loanProductApplication.setLoanPurposeId("");
        loanProductApplication.setLoanTermFrequency(4);
        loanProductApplication.setLoanTermFrequencyType(2);
        loanProductApplication.setLoanType(loanType);
        loanProductApplication.setLocale("en");
        loanProductApplication.setNumberOfRepayments(numberOfRepayments);
        loanProductApplication.setPrincipal(principal);
        loanProductApplication.setProductId(productId);
        loanProductApplication.setRepaymentEvery(repaymentEvery);
        loanProductApplication.setRepaymentFrequencyDayOfWeekType("");
        loanProductApplication.setRepaymentFrequencyNthDayType("");
        loanProductApplication.setRepaymentFrequencyType(repaymentFrequencyType);
        loanProductApplication.setRepaymentsStartingFromDate("");
        loanProductApplication.setSubmittedOnDate(submittedOnDate);
        loanProductApplication.setTransactionProcessingStrategyCode("creocore-strategy");

        ObjectMapper ow = new ObjectMapper();
        String jsonClient = ow.writeValueAsString(loanProductApplication);
        jsonClient = jsonClient.replace(":null", ":\"\"");

        return mifosXClient.newLoanAccountApplication(jsonClient);
    }

    @Tool(description = "Create an application for a new saving account using a product ID and a client's account number." +
            "You can optionally include an external ID)")
    JsonNode newSavingAccountApplication(@ToolArg(description = "Client Id (e.g. 1)") Integer clientId,
                                         @ToolArg(description = "Saving product ID (e.g. 1)") Integer productId,
                                         @ToolArg(description = "External Id (e.g CR03)", required = false) String externalId)
            throws IOException, JsonProcessingException {
        SavingProductApplication savingProductApplication = new SavingProductApplication();
        ObjectMapper ow = new ObjectMapper();
        LocalDate currentDate = LocalDate.now();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd MMMM yyyy");
        String formattedDate = currentDate.format(dtf);

        JsonNode templateSavingApplication = mifosXClient.getTemplateSavingsAccount(clientId, productId);

        savingProductApplication.setProductId(productId);
        savingProductApplication.setSubmittedOnDate(formattedDate);
        savingProductApplication.setFieldOfficerId(null);
        savingProductApplication.setExternalId("");
        savingProductApplication.setNominalAnnualInterestRate(templateSavingApplication.get("nominalAnnualInterestRate").asInt());
        savingProductApplication.setInterestCompoundingPeriodType(templateSavingApplication.get("interestCompoundingPeriodType").get("id").asInt());
        savingProductApplication.setInterestPostingPeriodType(templateSavingApplication.get("interestPostingPeriodType").get("id").asInt());
        savingProductApplication.setInterestCalculationType(templateSavingApplication.get("interestCalculationType").get("id").asInt());
        savingProductApplication.setInterestCalculationDaysInYearType(templateSavingApplication.get("interestCalculationDaysInYearType").get("id").asInt());
        savingProductApplication.setWithdrawalFeeForTransfers(templateSavingApplication.get("withdrawalFeeForTransfers").asText());
        savingProductApplication.setLockinPeriodFrequency(null);
        savingProductApplication.setLockinPeriodFrequencyType(null);
        savingProductApplication.setAllowOverdraft(templateSavingApplication.get("allowOverdraft").asText());
        savingProductApplication.setEnforceMinRequiredBalance(templateSavingApplication.get("enforceMinRequiredBalance").asText());

        JsonNode chargesNode = templateSavingApplication.get("charges");
        ArrayList<Charge> charges = ow.readerForListOf(Charge.class).readValue(chargesNode);

        savingProductApplication.setCharges(charges);
        savingProductApplication.setDateFormat("dd MMMM yyyy");
        savingProductApplication.setMonthDayFormat("dd MMMM");
        savingProductApplication.setLocale("en");
        savingProductApplication.setClientId(clientId);

        String jsonSavingProductApplication = ow.writeValueAsString(savingProductApplication);
        jsonSavingProductApplication = jsonSavingProductApplication.replace(":null", ":\"\"");

        return mifosXClient.newLoanAccountApplication(jsonSavingProductApplication);
    }

    private String getCurrencyCode (String currency) throws JsonProcessingException {
        JsonNode jsonResponse = mifosXClient.getCurrencies();

        CurrencyResponse response = mapper.treeToValue(jsonResponse, CurrencyResponse.class);
        List<Currency> selected = response.getSelectedCurrencyOptions();

        for (Currency c : selected) {
            if (c.getName().equalsIgnoreCase(currency) || c.getCode().equalsIgnoreCase(currency)) {
                return c.getCode();
            }
        }
        return null;
    }

    private Integer getCodeValueId (Integer codeId,String codeValueName) {
        for (JsonNode codeValue : mifosXClient.getCodeValues(codeId)) {
            if (codeValue.get("name").asText().equalsIgnoreCase(codeValueName)) {
                return codeValue.get("id").asInt();
            }
        }
        return null;
    }
}