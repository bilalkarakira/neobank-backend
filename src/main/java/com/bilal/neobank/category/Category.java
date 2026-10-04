package com.bilal.neobank.category;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "categories")
public class Category {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected Category() {
        // required by JPA
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public int getSortOrder() { return sortOrder; }
}
