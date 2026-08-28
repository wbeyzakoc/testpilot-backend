package com.testpilot.model.entity;

import jakarta.persistence.*;

// RunStep'in Oracle karsiligi -- mevcut RunStep.java (immutable, Jackson ile
// olusturuluyor) degismiyor, bu ayri/ek bir siniftir. run_steps tablosunda
// RunStep'te olmayan bir "id" kolonu var (JPA icin senkron PK gerekiyor).
@Entity
@Table(name = "MOBILE_RUN_STEPS")
public class RunStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "run_steps_seq_gen")
    @SequenceGenerator(name = "run_steps_seq_gen", sequenceName = "MOBILE_RUN_STEPS_SEQ", allocationSize = 1)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private RunEntity run;

    @Column(name = "step_no", nullable = false)
    private int stepNo;

    @Column(length = 20)
    private String action;

    @Column(length = 500)
    private String target;

    @Lob
    @Column(name = "reasoning")
    private String reasoning;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public RunEntity getRun() { return run; }
    public void setRun(RunEntity run) { this.run = run; }
    public int getStepNo() { return stepNo; }
    public void setStepNo(int stepNo) { this.stepNo = stepNo; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
}
