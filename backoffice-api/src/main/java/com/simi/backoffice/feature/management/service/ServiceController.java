package com.simi.backoffice.feature.management.service;

import com.simi.common.domain.entity.Service;
import com.simi.common.ulid.IdPrefix;
import com.simi.common.ulid.Ulid;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/services")
class ServiceController {

    private final ServiceRepository repo;

    ServiceController(ServiceRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<ServiceDto> list() {
        return repo.findAll().stream().map(ServiceDto::from).toList();
    }

    @GetMapping("/{id}")
    public ServiceDto get(@PathVariable String id) {
        return ServiceDto.from(load(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceDto create(@Valid @RequestBody ServiceDto in) {
        if (repo.existsByName(in.name())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "service.name already exists");
        }
        Service s = new Service();
        s.setId(Ulid.prefixed(IdPrefix.SERVICE));
        s.setName(in.name());
        s.setBaseUrl(in.baseUrl());
        return ServiceDto.from(repo.save(s));
    }

    @PutMapping("/{id}")
    public ServiceDto update(@PathVariable String id, @Valid @RequestBody ServiceDto in) {
        Service s = load(id);
        s.setName(in.name());
        s.setBaseUrl(in.baseUrl());
        return ServiceDto.from(repo.save(s));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        if (!repo.existsById(id)) return ResponseEntity.notFound().build();
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private Service load(String id) {
        return repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "service not found: " + id));
    }
}
