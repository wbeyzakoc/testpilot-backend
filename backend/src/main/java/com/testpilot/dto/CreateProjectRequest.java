package com.testpilot.dto;

import java.util.List;

public class CreateProjectRequest {
    private String name;
    private List<Long> memberIds;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Long> getMemberIds() { return memberIds; }
    public void setMemberIds(List<Long> memberIds) { this.memberIds = memberIds; }
}
