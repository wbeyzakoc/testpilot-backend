package com.testpilot.model;

// Run.getSuites() listesindeki her eleman -- bir testin ait olduğu suite'lerden
// biri. Suite entity'sine ilişki kurmak yerine (projectId/projectName'deki gibi)
// düz id+name kopyası tutuluyor, Run'ın Oracle/JSON'a serileştirilmesi
// lazy-loading sorunu yaşamadan çalışsın diye.
public class SuiteRef {
    private Long id;
    private String name;

    public SuiteRef() {
    }

    public SuiteRef(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
