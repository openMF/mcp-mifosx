package org.community.mifos.agentic.loan.model;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Subset of a Fineract loan product used to adapt applications before submit.
 * Portable across products – populated from GET /loanproducts/{id}.
 */
public class LoanProductTemplate implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer productId;
    private String name;
    private String shortName;
    private BigDecimal minPrincipal;
    private BigDecimal maxPrincipal;
    private BigDecimal principal;          // product default
    private Integer minNumberOfRepayments;
    private Integer maxNumberOfRepayments;
    private Integer numberOfRepayments;    // product default
    private Integer repaymentEvery;
    private Integer repaymentFrequencyType; // 0=days,1=weeks,2=months
    private BigDecimal interestRatePerPeriod;
    private Integer amortizationType;
    private Integer interestType;
    private Integer interestCalculationPeriodType;
    private String transactionProcessingStrategyCode;
    private Integer loanTermFrequency;
    private Integer loanTermFrequencyType;
    private Map<String, Object> raw;

    public LoanProductTemplate() {}

    public Integer getProductId() { return productId; }
    public void setProductId(Integer productId) { this.productId = productId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getShortName() { return shortName; }
    public void setShortName(String shortName) { this.shortName = shortName; }
    public BigDecimal getMinPrincipal() { return minPrincipal; }
    public void setMinPrincipal(BigDecimal minPrincipal) { this.minPrincipal = minPrincipal; }
    public BigDecimal getMaxPrincipal() { return maxPrincipal; }
    public void setMaxPrincipal(BigDecimal maxPrincipal) { this.maxPrincipal = maxPrincipal; }
    public BigDecimal getPrincipal() { return principal; }
    public void setPrincipal(BigDecimal principal) { this.principal = principal; }
    public Integer getMinNumberOfRepayments() { return minNumberOfRepayments; }
    public void setMinNumberOfRepayments(Integer minNumberOfRepayments) { this.minNumberOfRepayments = minNumberOfRepayments; }
    public Integer getMaxNumberOfRepayments() { return maxNumberOfRepayments; }
    public void setMaxNumberOfRepayments(Integer maxNumberOfRepayments) { this.maxNumberOfRepayments = maxNumberOfRepayments; }
    public Integer getNumberOfRepayments() { return numberOfRepayments; }
    public void setNumberOfRepayments(Integer numberOfRepayments) { this.numberOfRepayments = numberOfRepayments; }
    public Integer getRepaymentEvery() { return repaymentEvery; }
    public void setRepaymentEvery(Integer repaymentEvery) { this.repaymentEvery = repaymentEvery; }
    public Integer getRepaymentFrequencyType() { return repaymentFrequencyType; }
    public void setRepaymentFrequencyType(Integer repaymentFrequencyType) { this.repaymentFrequencyType = repaymentFrequencyType; }
    public BigDecimal getInterestRatePerPeriod() { return interestRatePerPeriod; }
    public void setInterestRatePerPeriod(BigDecimal interestRatePerPeriod) { this.interestRatePerPeriod = interestRatePerPeriod; }
    public Integer getAmortizationType() { return amortizationType; }
    public void setAmortizationType(Integer amortizationType) { this.amortizationType = amortizationType; }
    public Integer getInterestType() { return interestType; }
    public void setInterestType(Integer interestType) { this.interestType = interestType; }
    public Integer getInterestCalculationPeriodType() { return interestCalculationPeriodType; }
    public void setInterestCalculationPeriodType(Integer interestCalculationPeriodType) { this.interestCalculationPeriodType = interestCalculationPeriodType; }
    public String getTransactionProcessingStrategyCode() { return transactionProcessingStrategyCode; }
    public void setTransactionProcessingStrategyCode(String transactionProcessingStrategyCode) { this.transactionProcessingStrategyCode = transactionProcessingStrategyCode; }
    public Integer getLoanTermFrequency() { return loanTermFrequency; }
    public void setLoanTermFrequency(Integer loanTermFrequency) { this.loanTermFrequency = loanTermFrequency; }
    public Integer getLoanTermFrequencyType() { return loanTermFrequencyType; }
    public void setLoanTermFrequencyType(Integer loanTermFrequencyType) { this.loanTermFrequencyType = loanTermFrequencyType; }
    public Map<String, Object> getRaw() { return raw; }
    public void setRaw(Map<String, Object> raw) { this.raw = raw; }

    public Map<String, Object> toSummaryMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("productId", productId);
        m.put("name", name);
        m.put("minPrincipal", minPrincipal);
        m.put("maxPrincipal", maxPrincipal);
        m.put("minNumberOfRepayments", minNumberOfRepayments);
        m.put("maxNumberOfRepayments", maxNumberOfRepayments);
        m.put("defaultPrincipal", principal);
        m.put("defaultNumberOfRepayments", numberOfRepayments);
        m.put("interestRatePerPeriod", interestRatePerPeriod);
        return m;
    }
}
