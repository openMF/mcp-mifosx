package org.mifos.community.ai.mcp.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class LoanOfficer {
    Integer id;
    String firstname;
    String lastname;
    String displayName;
    Integer officeId;
    String officeName;
    String isLoanOfficer;
    String isActive;
    @JsonDeserialize(using = DateArrayDeserializer.class)
    @JsonSerialize()
    String joiningDate;
}
