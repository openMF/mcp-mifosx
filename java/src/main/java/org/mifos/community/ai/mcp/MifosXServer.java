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
        if(externalId != null){
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
                            @ToolArg(description = "Activation Date (e.g. 22 April 2025)", required = false) String activationDate)
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


    @Tool(description = "Create an application for a new saving account using a product ID and a client's account number." +
            "You can optionally include an external ID)")
    JsonNode newSavingsAccountApplication(@ToolArg(description = "Client Id (e.g. 1)") Integer clientId,
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

        return mifosXClient.newSavingAccountApplication(jsonSavingProductApplication);
    }

    @Tool(description = "Approve a savings account using the account number. " +
            "You can optionally include a note for approval consideration.")
    JsonNode approveSavingsAccount(@ToolArg(description = "Account number (e.g. 1)") Integer accountNumber,
                                   @ToolArg(description = "Note for approval consideration (e.g. Some observation)", required = false) String note)
            throws JsonProcessingException {
        SavingAccountApproval savingAccountApproval = new SavingAccountApproval();
        String command = "approve";
        ObjectMapper ow = new ObjectMapper();
        LocalDate currentDate = LocalDate.now();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd MMMM yyyy");
        String formattedDate = currentDate.format(dtf);

        savingAccountApproval.setApprovedOnDate(formattedDate);
        savingAccountApproval.setDateFormat("dd MMMM yyyy");
        savingAccountApproval.setLocale("en");
        savingAccountApproval.setNote(note);

        String jsonSavingsAccountActivation = ow.writeValueAsString(savingAccountApproval);
        return mifosXClient.approveSavingsAccount(accountNumber,command,jsonSavingsAccountActivation);
    }

    @Tool(description = "Activate a savings account using the account number.")
    JsonNode activateSavingsAccount(@ToolArg(description = "Account number (e.g. 1)") Integer accountNumber)
            throws JsonProcessingException {
        SavingAccountActivation savingAccountActivation = new SavingAccountActivation();
        String command = "activate";
        ObjectMapper ow = new ObjectMapper();
        LocalDate currentDate = LocalDate.now();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd MMMM yyyy");
        String formattedDate = currentDate.format(dtf);

        savingAccountActivation.setActivatedOnDate(formattedDate);
        savingAccountActivation.setDateFormat("dd MMMM yyyy");
        savingAccountActivation.setLocale("en");

        String jsonSavingsAccountActivation = ow.writeValueAsString(savingAccountActivation);
        return mifosXClient.activateSavingsAccount(accountNumber,command,jsonSavingsAccountActivation);
    }

    @Tool(description = "Create a savings transaction (deposit or withdrawal) for a specific client. " +
            "Provide: account number, transaction, payment type, " +
            "transaction amount, and optionally a note and transaction date. " +
            "If no date is provided, the current date will be used. " +
            "Use this to register client savings transactions.")
    JsonNode newSavingsTransaction(
            @ToolArg(description = "Account Number for whom the transaction is being made (e.g. 1).") Integer accountNumber,
            @ToolArg(description = "Type of transaction: either DEPOSIT or WITHDRAWAL. (e.g. deposit)") String transaction,
            @ToolArg(description = "Optional note or description for the transaction (e.g. NOTE).", required = false) String note,
            @ToolArg(description = "Payment method used (e.g. Money Transfer).") String paymentType,
            @ToolArg(description = "Amount of money involved in the transaction (e.g. 1000).") double transactionAmount,
            @ToolArg(description = "Optional transaction date in 'dd MMMM yyyy' format (e.g. 09 May 2025). " +
                    "If not provided, current date is used.", required = false) String transactionDate)
            throws JsonProcessingException{
        JsonNode jsonTemplate = mifosXClient.getSavingsTransactionTemplate(accountNumber);
        ObjectMapper ow = new ObjectMapper();
        SavingsTransactionTemplate template = ow.treeToValue(jsonTemplate, SavingsTransactionTemplate.class);

        SavingsTransaction savingsTransaction = new SavingsTransaction();


        try {
            PaymentType matchingPaymentType = template.getPaymentTypeOptions().stream()
                    .filter(pt -> pt.getName().equalsIgnoreCase(paymentType))
                    .findFirst()
                    .orElse(null);

            if (matchingPaymentType != null) {
                savingsTransaction.setPaymentTypeId(matchingPaymentType.getId());
            }
        } catch (NullPointerException e) {
            log.error("The provided payment type does not match any of the available options", e);
        }

        savingsTransaction.setDateFormat("dd MMMM yyyy");
        savingsTransaction.setLocale("en");
        savingsTransaction.setNote(Optional.ofNullable(note).orElse(""));

        savingsTransaction.setTransactionAmount(transactionAmount);
        savingsTransaction.setTransactionDate(Optional.ofNullable(transactionDate).orElse(template.getDate()));

        String jsonSavingsTransaction = ow.writeValueAsString(savingsTransaction);
        jsonSavingsTransaction = jsonSavingsTransaction.replace(":null", ":\"\"");

        return mifosXClient.newSavingsTransaction(accountNumber, transaction.toLowerCase(), jsonSavingsTransaction);
    }

    @Tool(description = "Create a default loan product. " +
            "Provide only the following inputs: name, short name, principal, number of repayments, nominal interest rate, " +
            "repayment frequency, repayment frequency type (valid values: DAYS, WEEKS, MONTHS...), and currency. " +
            "All other values will be automatically set with default configuration. " +
            "Use this to quickly initialize standard loan products.")
    JsonNode createDefaultLoanProduct (@ToolArg(description = "Full name of the loan product (e.g. BRONZE).") String name,
                                       @ToolArg(description = "Short code for the loan product (e.g. LB01).") String shortName,
                                       @ToolArg(description = "Total loan amount (e.g. 10000).") Double principal,
                                       @ToolArg(description = "Number of repayments (e.g. 5).") Integer numberOfRepayments,
                                       @ToolArg(description = "Nominal interest rate per period in percentage (e.g. 10.0).") Double nominalInterestRate,
                                       @ToolArg(description = "Interval between repayments (e.g. 2).") Integer repaymentFrequency,
                                       @ToolArg(description = "Unit of time for repayment (e.g. MONTHS).") String repaymentFrequencyType,
                                       @ToolArg(description = "Currency to use, either code or name (e.g. USD or US Dollar).") String currency)
        throws JsonProcessingException, IOException {
        ObjectMapper ow = new ObjectMapper();

        LoanProduct loanProduct = new LoanProduct();
        AllowAttributeOverrides allowAttributeOverrides = new AllowAttributeOverrides();

        List<Types> interestRateVariationsForBorrowerCycle = new ArrayList<>();
        List<Types> numberOfRepaymentVariationsForBorrowerCycle = new ArrayList<>();
        List<Types> principalVariationsForBorrowerCycle = new ArrayList<>();


        JsonNode repaymentOptionsNode = mifosXClient.getLoanProductTemplate().get("repaymentFrequencyTypeOptions");
        List<Types> repaymentOptions = ow.readerForListOf(Types.class).readValue(repaymentOptionsNode);


        loanProduct.setName(name);
        loanProduct.setShortName(shortName);
        loanProduct.setPrincipal(principal);
        loanProduct.setNumberOfRepayments(numberOfRepayments);
        loanProduct.setInterestRatePerPeriod(nominalInterestRate);
        loanProduct.setRepaymentEvery(repaymentFrequency);

        for (Types repaymentOption : repaymentOptions) {
            if (repaymentOption.getValue().equalsIgnoreCase(repaymentFrequencyType)) {
                loanProduct.setRepaymentFrequencyType(repaymentOption.getId());
            }
        }

        loanProduct.setCurrencyCode(getCurrencyCode(currency));

        loanProduct.setAllowAttributeOverrides(allowAttributeOverrides);

        loanProduct.setInterestRateVariationsForBorrowerCycle(interestRateVariationsForBorrowerCycle);
        loanProduct.setNumberOfRepaymentVariationsForBorrowerCycle(numberOfRepaymentVariationsForBorrowerCycle);
        loanProduct.setPrincipalVariationsForBorrowerCycle(principalVariationsForBorrowerCycle);

        ArrayList<Charge> charges = new ArrayList<>();
        loanProduct.setCharges(charges);


        ObjectWriter owf = new ObjectMapper().writer().withDefaultPrettyPrinter();
        String jsonDefaultLoanProduct = ow.writeValueAsString(loanProduct);

        log.info("\n\n ******** Contenido de loanProduct enviado al backend ********\n" + jsonDefaultLoanProduct + "\n\n");

        return mifosXClient.createDefaultLoanProduct(jsonDefaultLoanProduct);
    }

    @Tool(description = "Create an application for a new loan account using a product ID and a client's account number. " +
            "The following fields are required: clientId, loanType, and productId. " +
            "Optional fields include numberOfRepayments, loanType, principal, productId, repaymentEvery, and expectedDisbursementDate.")
    JsonNode newLoanAccountApplication(@ToolArg(description = "Client Id (e.g. 1)") Integer clientId,
                                       @ToolArg(description = "Loan Type (e.g. Individual)") String loanType,
                                       @ToolArg(description = "Product Id (e.g 2)") Integer productId,
                                       @ToolArg(description = "Expected Disbursement Date (e.g 14 April 2025)", required = false) String expectedDisbursementDate,
                                       //@ToolArg(description = "Interest Rate Frequency Type (e.g 2)") String interestRateFrequencyType,
                                       //@ToolArg(description = "Interest Rate Per Period (e.g 5)") Double interestRatePerPeriod,
                                       //@ToolArg(description = "Is Equal Amortization (e.g \"false\")") String isEqualAmortization,
                                       @ToolArg(description = "Number Of Repayments (e.g 2)", required = false) Integer numberOfRepayments,
                                       @ToolArg(description = "Principal (e.g 1000)", required = false) Double principal,
                                       @ToolArg(description = "Repayment Every (e.g 2)", required = false) Integer repaymentEvery
                                       //@ToolArg(description = "Repayment Frequency Type (e.g 2)") String repaymentFrequencyType,
                                       /*@ToolArg(description = "Submitted on Date (e.g 14 April 2025)") String submittedOnDate*/)
            throws JsonProcessingException {
        LoanProductApplication loanProductApplication = new LoanProductApplication();
        ArrayList<Charge> charges = new ArrayList<>();
        ArrayList<AddLoanProductCollateralRequest> colateral = new ArrayList<>();


        JsonNode loanApplicationTemplateJson = mifosXClient.getLoanProductApplicationTemplate("true","true",productId,clientId,loanType.toLowerCase());
        log.info("\n\n ******** JSON LOAN APPLICATION TEMPLATE ********\n\n" + loanApplicationTemplateJson + "\n\n");
        LoanProductApplicationTemplate lpat = mapper.treeToValue(loanApplicationTemplateJson, LoanProductApplicationTemplate.class);
        log.info("\n\n ******** TEMPLATE HAS BEEN DESERIALIZED ********\n\n" );

        loanProductApplication.setAllowPartialPeriodInterestCalcualtion("false");
        loanProductApplication.setAmortizationType(lpat.getAmortizationType().getId());
        loanProductApplication.setCharges(charges);
        loanProductApplication.setClientId(clientId);
        loanProductApplication.setCollateral(colateral);
        loanProductApplication.setCreateStandingInstructionAtDisbursement("");
        loanProductApplication.setDateFormat("dd MMMM yyyy");
        loanProductApplication.setExpectedDisbursementDate(Optional.ofNullable(expectedDisbursementDate).orElse(lpat.getExpectedDisbursementDate()));
        loanProductApplication.setExternalId(Optional.ofNullable(lpat.getProduct().getExternalId()).orElse(""));
        loanProductApplication.setFundId(null); // It should be included in the template
        loanProductApplication.setInterestCalculationPeriodType(lpat.getInterestCalculationPeriodType().getId());
        loanProductApplication.setInterestChargedFromDate(null);
        loanProductApplication.setInterestRateFrequencyType(lpat.getInterestRateFrequencyType().getId());
        loanProductApplication.setInterestRatePerPeriod(lpat.getInterestRatePerPeriod());
        loanProductApplication.setInterestType(lpat.getInterestType().getId());
        loanProductApplication.setIsEqualAmortization(lpat.getIsEqualAmortization());
        loanProductApplication.setIsTopup(lpat.getIsTopup());
        loanProductApplication.setLinkAccountId(null); // It should be included in the template
        loanProductApplication.setLoanIdToClose(null); // It should be included in the template
        loanProductApplication.setLoanOfficerId(null); // It should be included in the template
        loanProductApplication.setLoanPurposeId(null); // It should be included in the template
        loanProductApplication.setLoanTermFrequency(lpat.getTermFrequency());
        loanProductApplication.setLoanTermFrequencyType(lpat.getTermPeriodFrequencyType().getId());
        loanProductApplication.setLoanType(loanType.toLowerCase());
        loanProductApplication.setLocale("en");
        loanProductApplication.setNumberOfRepayments(Optional.ofNullable(numberOfRepayments).orElse(lpat.getNumberOfRepayments()));
        loanProductApplication.setPrincipal(Optional.ofNullable(principal).orElse(lpat.getPrincipal()));
        loanProductApplication.setProductId(productId);
        loanProductApplication.setRepaymentEvery(Optional.ofNullable(repaymentEvery).orElse(lpat.getRepaymentEvery()));
        loanProductApplication.setRepaymentFrequencyDayOfWeekType(null);
        loanProductApplication.setRepaymentFrequencyNthDayType(null);
        loanProductApplication.setRepaymentFrequencyType(lpat.getRepaymentFrequencyType().getId());
        loanProductApplication.setRepaymentsStartingFromDate("");

        LocalDate currentDate = LocalDate.now();
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern(loanProductApplication.getDateFormat());
        String formattedDate = currentDate.format(dtf);

        loanProductApplication.setSubmittedOnDate(formattedDate);
        loanProductApplication.setTransactionProcessingStrategyCode(lpat.getTransactionProcessingStrategyCode());

        ObjectMapper ow = new ObjectMapper();
        String jsonLoanAccountApplication = ow.writeValueAsString(loanProductApplication);
        jsonLoanAccountApplication = jsonLoanAccountApplication.replace(":null", ":\"\"");

        log.info("\n\n ******** JSON STRUCTURE FOR THE REQUEST ********\n\n" + jsonLoanAccountApplication + "\n\n");
        log.info("\n\n ******** JSON STRUCTURE FOR THE REQUEST ********\n\n" );
        return mifosXClient.newLoanAccountApplication(jsonLoanAccountApplication);
    }

    @Tool(description = "Approve a loan account using the account number. " +
            "Optionally, include the approval date, expected disbursement date, approved loan amount, and a note.")
    JsonNode approveLoanAccount(@ToolArg(description = "Account number (e.g. 1)") Integer accountNumber,
                                   @ToolArg(description = "Account approval date (e.g. 29 May 2025).", required = false) String approvalDate,
                                   @ToolArg(description = "Expected disbursement date (e.g. Some observation)", required = false) String expectedDisbursementDate,
                                   @ToolArg(description = "Approved loan amount for the account (e.g. Some 10000)", required = false) Double approvedLoanAmount,
                                   @ToolArg(description = "Note for approval consideration (e.g. Some observation)", required = false) String note)
            throws JsonProcessingException {
        LoanAccountApproval loanAccountApproval = new LoanAccountApproval();
        JsonNode jsonNode = mifosXClient.getLoanApprovalTemplate(accountNumber,"approval");
        ObjectMapper ow = new ObjectMapper();
        LoanAccountApprovalTemplate loanAccountApprovalTemplate = ow.treeToValue(jsonNode, LoanAccountApprovalTemplate.class);
        String command = "approve";

        loanAccountApproval.setDateFormat("dd MMMM yyyy");

        if (approvalDate != null) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(loanAccountApproval.getDateFormat());
            LocalDate ad = LocalDate.parse(approvalDate, formatter);
            LocalDate adTemplate = LocalDate.parse(loanAccountApprovalTemplate.getApprovalDate(), formatter);
            if (ad.isAfter(adTemplate)) {
                loanAccountApproval.setApprovedOnDate(approvalDate);
            }
            else {
                throw new IllegalArgumentException("The approval date cannot be earlier than the application date.");
            }
        }
        else {
            loanAccountApproval.setApprovedOnDate(loanAccountApprovalTemplate.getApprovalDate());
        }

        if (expectedDisbursementDate != null) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(loanAccountApproval.getDateFormat());
            LocalDate expectedDate = LocalDate.parse(expectedDisbursementDate, formatter);
            LocalDate expectedDateTemplate = LocalDate.parse(loanAccountApprovalTemplate.getApprovalDate(), formatter);
            if (expectedDate.isAfter(expectedDateTemplate)) {
                loanAccountApproval.setExpectedDisbursementDate(expectedDisbursementDate);
            }
            else {
                throw new IllegalArgumentException("The disbursement date cannot be earlier than the approval date.");
            }
        }
        else {
            loanAccountApproval.setExpectedDisbursementDate(loanAccountApprovalTemplate.getApprovalDate());
        }

        loanAccountApproval.setApprovedLoanAmount(Optional.ofNullable(approvedLoanAmount).orElse(loanAccountApprovalTemplate.getApprovalAmount()));
        loanAccountApproval.setNote(Optional.ofNullable(note).orElse(""));
        loanAccountApproval.setLocale("en");

        String jsonLoanAccountActivation = ow.writeValueAsString(loanAccountApproval);
        return mifosXClient.approveLoanAccount(accountNumber,command,jsonLoanAccountActivation);
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