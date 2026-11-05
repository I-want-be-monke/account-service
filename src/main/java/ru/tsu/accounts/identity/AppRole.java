package ru.tsu.accounts.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_role")
public class AppRole {
    @Id
    @Column(length = 24)
    private String code;

    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;

    protected AppRole() { }

    public String getCode() { return code; }
    public String getDisplayName() { return displayName; }
}
