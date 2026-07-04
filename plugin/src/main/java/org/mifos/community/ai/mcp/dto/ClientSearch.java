package org.mifos.community.ai.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ClientSearch {
    public Request request;
    public Integer page;
    public Integer size;
}
