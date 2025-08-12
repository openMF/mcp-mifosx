package org.mifos.community.ai.mcp.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class Timeline {
    @JsonDeserialize(using = DateArrayDeserializer.class)
    String submittedOnDate;
    String submittedByUsername;
    String submittedByFirstname;
    String submittedByLastname;
    @JsonDeserialize(using = DateArrayDeserializer.class)
    String expectedDisbursementDate;
    @JsonDeserialize(using = DateArrayDeserializer.class)
    String actualMaturityDate;
    @JsonDeserialize(using = DateArrayDeserializer.class)
    String expectedMaturityDate;
}
