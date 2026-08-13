package com.testpilot.model;

public class AgentAction {//LLM'in ham JSON cevabının Java karşılığı, kullanıcıya gösterilecek sadeleştirilmiş hâli.
    private String action;     // "tap", "swipe", "type", "wait", "done", "fail"
    private Integer x;
    private Integer y;
    private String text;       // "type" aksiyonu için girilecek metin
    private String direction;  // "swipe" için: up, down, left, right
    private String reasoning;  // LLM'in kararının kısa açıklaması

    // getters & setters
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Integer getX() { return x; }
    public void setX(Integer x) { this.x = x; }
    public Integer getY() { return y; }
    public void setY(Integer y) { this.y = y; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }

    private String target; // ör: "Sepetim ikonu", "Arama kutusu" - insan-okunabilir açıklama

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }}


/*Olası soru: "Neden AgentAction ile RunStep ayrı sınıflar?" →
 Biri (AgentAction) LLM'den gelen ham teknik karar (x,y koordinatı içerir),
 diğeri (RunStep) frontend'e gönderilen, kullanıcının anlayacağı sadeleştirilmiş kayıt.
 İkisini ayırmak, iç mantık ile dışa açılan veriyi birbirinden bağımsızlaştırıyor.*/