package com.testpilot.model;

public class RunStep {//tek bir adımın kaydı
    private int step;
    private String action;   // "tap" | "type" | "done" | "failed"
    private String target;
    private String reasoning;

    public RunStep(int step, String action, String target, String reasoning) {
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