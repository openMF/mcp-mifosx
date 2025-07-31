package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ParsedAmount {
    String source;
    Double parsedValue;
}
