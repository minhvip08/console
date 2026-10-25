package com.simi.common.domain.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Local mock configuration; not an Elton or Shopee API schema. */
@Entity
@Table(name = "shopee_run")
public class ShopeeRun {
    @Id
    @Column(name = "execution_id")
    private Long id;

    @Column(name = "connection_id")
    private Long connectionId;

    @Column(name = "kind")
    private String kind;

    @Column(name = "shop_account_id")
    private String shopAccountId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_types", columnDefinition = "jsonb")
    private java.util.List<String> dataTypes = java.util.List.of();

    @Column(name = "first_load_date")
    private java.time.LocalDate firstLoadDate;

    @Column(name = "lookback_days")
    private int lookbackDays;

    @Column(name = "run_at")
    private java.time.Instant runAt;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getConnectionId() { return connectionId; }
    public void setConnectionId(Long value) { this.connectionId = value; }
    public String getKind() { return kind; }
    public void setKind(String value) { this.kind = value; }
    public String getShopAccountId() { return shopAccountId; }
    public void setShopAccountId(String value) { this.shopAccountId = value; }
    public java.util.List<String> getDataTypes() { return dataTypes; }
    public void setDataTypes(java.util.List<String> value) { this.dataTypes = value; }
    public java.time.LocalDate getFirstLoadDate() { return firstLoadDate; }
    public void setFirstLoadDate(java.time.LocalDate value) { this.firstLoadDate = value; }
    public int getLookbackDays() { return lookbackDays; }
    public void setLookbackDays(int value) { this.lookbackDays = value; }
    public java.time.Instant getRunAt() { return runAt; }
    public void setRunAt(java.time.Instant value) { this.runAt = value; }
}
