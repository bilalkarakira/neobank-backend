package com.bilal.neobank.category;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class CategoryController {

    public record CategoryResponse(UUID id, String name) {
    }

    private final CategoryRepository repository;

    public CategoryController(CategoryRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/categories")
    public List<CategoryResponse> list() {
        return repository.findAllByOrderBySortOrderAsc().stream()
                .map(c -> new CategoryResponse(c.getId(), c.getName())).toList();
    }
}
