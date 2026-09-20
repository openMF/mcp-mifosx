package org.community.mifos.agentic.loan.activity;

import org.community.mifos.agentic.loan.model.AssessmentResult;
import org.community.mifos.agentic.loan.model.LoanApplication;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.Map;

@ActivityInterface
public interface LoanActivities {

    @ActivityMethod
    Map<String, Object> fetchBankAccount(String applicantId);

    @ActivityMethod
    Map<String, Object> fetchCreditReportCibil(String applicantId);

    @ActivityMethod
    Map<String, Object> fetchCreditReportExperian(String applicantId);

    @ActivityMethod
    Map<String, Object> processDocument(String path, String applicantId, String expectedDisplayName);

    @ActivityMethod
    AssessmentResult incomeAssessment(LoanApplication app, Map<String, Object> bank, Map<String, Object> credit);

    @ActivityMethod
    AssessmentResult expenseAssessment(LoanApplication app, Map<String, Object> bank);

    @ActivityMethod
    AssessmentResult creditAssessment(LoanApplication app, Map<String, Object> credit);
}
