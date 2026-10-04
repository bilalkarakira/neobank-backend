package com.bilal.neobank.category;

public class CategoryNotFoundException extends RuntimeException {

    public CategoryNotFoundException() {
        super("The category does not exist");
    }
}
