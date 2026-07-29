package com.vulnflow.asset;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/assets")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @PostMapping
    public ResponseEntity<AssetDtos.Response> create(@Valid @RequestBody AssetDtos.CreateRequest request) {
        AssetDtos.Response response = assetService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/assets/" + response.id())).body(response);
    }

    @GetMapping
    public Page<AssetDtos.Response> findAll(
            @PageableDefault(
                    size = 20,
                    sort = {"createdAt", "id"},
                    direction = Sort.Direction.DESC)
            Pageable pageable) {
        return assetService.findAll(pageable);
    }

    @GetMapping("/{id}")
    public AssetDtos.Response findById(@PathVariable UUID id) {
        return assetService.findById(id);
    }
}
