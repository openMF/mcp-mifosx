package org.community.mifos.agentic.loan.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Application values after clamping to the active loan product template.
 */
public class AdaptedLoanRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private BigDecimal principal;
    private int numberOfRepayments;
    private int loanTermFrequency;
    private int loanTermFrequencyType;
    private int repaymentEvery;
    private int repaymentFrequencyType;
    private BigDecimal interestRatePerPeriod;
    private Integer productId;
    private String productName;
    private final List<String> adjustments = new ArrayList<>();

    public BigDecimal getPrincipal() { return principal; }
    public void setPrincipal(BigDecimal principal) { this.principal = principal; }
    public int getNumberOfRepayments() { return numberOfRepayments; }
    public void setNumberOfRepayments(int numberOfRepayments) { this.numberOfRepayments = numberOfRepayments; }
    public int getLoanTermFrequency() { return loanTermFrequency; }
    public void setLoanTermFrequency(int loanTermFrequency) { this.loanTermFrequency = loanTermFrequency; }
    public int getLoanTermFrequencyType() { return loanTermFrequencyType; }
    public void setLoanTermFrequencyType(int loanTermFrequencyType) { this.loanTermFrequencyType = loanTermFrequencyType; }
    public int getRepaymentEvery() { return repaymentEvery; }
    public void setRepaymentEvery(int repaymentEvery) { this.repaymentEvery = repaymentEvery; }
    public int getRepaymentFrequencyType() { return repaymentFrequencyType; }
    public void setRepaymentFrequencyType(int repaymentFrequencyType) { this.repaymentFrequencyType = repaymentFrequencyType; }
    public BigDecimal getInterestRatePerPeriod() { return interestRatePerPeriod; }
    public void setInterestRatePerPeriod(BigDecimal interestRatePerPeriod) { this.interestRatePerPeriod = interestRatePerPeriod; }
    public Integer getProductId() { return productId; }
    public void setProductId(Integer productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public List<String> getAdjustments() { return adjustments; }
    public void addAdjustment(String msg) { adjustments.add(msg); }
}
