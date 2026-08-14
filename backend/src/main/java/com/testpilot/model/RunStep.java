package com.testpilot.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class RunStep {
    private final int step;
    private final String action;
    private final String target;
    private final String reasoning;

    @JsonCreator
    public RunStep(
            @JsonProperty("step") int step,
            @JsonProperty("action") String action,
            @JsonProperty("target") String target,
            @JsonProperty("reasoning") String reasoning) {
        this.step = step;
        this.action = action;
        this.target = target;
        this.reasoning = reasoning;
    }

    public int getStep() { return step; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public String getReasoning() { return reasoning; }
}