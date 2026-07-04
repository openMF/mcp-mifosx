package org.mifos.community.ai.mcp.dto;

import java.util.ArrayList;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Client {
    
    int officeId;
    int legalFormId;
    String isStaff;
    boolean active;
    String submittedOnDate;
    String firstname;
    String lastname;
    String emailAddress;
    String mobileNo;
    String externalId;
    String  activationDate;
    ArrayList<FamilyMember> familyMembers;
    String dateFormat;
    String locale;
    
}
