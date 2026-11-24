package com.smartparking.common.api;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/catalogs")
public class CatalogController {
    private final CatalogService catalogs;
    public CatalogController(CatalogService catalogs) { this.catalogs=catalogs; }
    @GetMapping("/{key}/definition") public CatalogService.Catalog definition(@PathVariable String key) { return catalogs.definition(key); }
    @GetMapping("/{key}") public Map<String,Object> list(@PathVariable String key,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String search) { return catalogs.list(key,page,size,search); }
    @PostMapping("/{key}") public Map<String,Object> create(@PathVariable String key,@RequestBody Map<String,Object> body) { return catalogs.save(key,null,body); }
    @PutMapping("/{key}/{id}") public Map<String,Object> update(@PathVariable String key,@PathVariable UUID id,@RequestBody Map<String,Object> body) { return catalogs.save(key,id,body); }
    @DeleteMapping("/{key}/{id}") public void deactivate(@PathVariable String key,@PathVariable UUID id) { catalogs.deactivate(key,id); }
}
