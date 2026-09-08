package com.testpilot.dto;

import java.util.List;

// POST /projects/{id}/members gövdesi — users.tsx'teki "seçili kullanıcıları
// projeye ekle" toolbar'ı ile birebir eşleşir. PUT /projects/{id}'nin aksine
// (o üye listesini TAMAMEN gönderilenle değiştirir) burada sadece verilen
// userId'ler mevcut üye listesine EKLENİR — zaten üye olanlar etkilenmez,
// projenin diğer üyeleri silinmez.
public class AddProjectMembersRequest {
    private List<Long> userIds;

    public List<Long> getUserIds() { return userIds; }
    public void setUserIds(List<Long> userIds) { this.userIds = userIds; }
}
