package com.simi.common.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Local mock configuration; not an Elton or Shopee API schema. */
@Entity
@Table(name = "shopee_source")
public class ShopeeSource {
    @Version
    private long version;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "kind")
    private String kind;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "description")
    private String description;

    @Column(name = "enabled")
    private boolean enabled;

    @Column(name = "deleted")
    private boolean deleted;

    @Column(name = "shop_account_id")
    private String shopAccountId;

    @Column(name = "authorized_until")
    private java.time.Instant authorizedUntil;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_user_ids", columnDefinition = "jsonb")
    private java.util.List<String> allowedUserIds = java.util.List.of();

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public String getKind() { return kind; }
    public void setKind(String value) { this.kind = value; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String value) { this.displayName = value; }
    public String getDescription() { return description; }
    public void setDescription(String value) { this.description = value; }
    public boolean getEnabled() { return enabled; }
    public void setEnabled(boolean value) { this.enabled = value; }
    public boolean getDeleted() { return deleted; }
    public void setDeleted(boolean value) { this.deleted = value; }
    public String getShopAccountId() { return shopAccountId; }
    public void setShopAccountId(String value) { this.shopAccountId = value; }
    public java.time.Instant getAuthorizedUntil() { return authorizedUntil; }
    public void setAuthorizedUntil(java.time.Instant value) { this.authorizedUntil = value; }
    public java.util.List<String> getAllowedUserIds() { return allowedUserIds; }
    public void setAllowedUserIds(java.util.List<String> value) { this.allowedUserIds = value; }
}
