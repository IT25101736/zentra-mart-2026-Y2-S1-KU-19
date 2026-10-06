package com.zentramart.backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Public read-only list so any page can load categories from the database.
@RestController
@RequestMapping("/api/categories")
@CrossOrigin(origins = "*")
public class CategoryController {

    private final CategoryRepository categoryRepository;

    @Autowired
    public CategoryController(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    // GET /api/categories
    @GetMapping
    public List<Category> getAll() {
        return categoryRepository.findAll();
    }
}
