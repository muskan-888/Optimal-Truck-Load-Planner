package com.smartload.controller;

import com.smartload.model.OptimizeRequest;
import com.smartload.model.OptimizeResponse;
import com.smartload.service.LoadOptimizerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/load-optimizer")
public class LoadOptimizerController {

    private final LoadOptimizerService optimizerService;

    public LoadOptimizerController(LoadOptimizerService optimizerService) {
        this.optimizerService = optimizerService;
    }

    /**
     * POST /api/v1/load-optimizer/optimize
     *
     * Accepts a truck + list of candidate orders and returns the optimal
     * combination that maximises payout while respecting all constraints.
     *
     * HTTP 200 – success (even when no orders can be selected)
     * HTTP 400 – invalid / missing fields
     * HTTP 413 – more than 22 orders submitted
     */
    @PostMapping("/optimize")
    public ResponseEntity<OptimizeResponse> optimize(
            @Valid @RequestBody OptimizeRequest request
    ) {
        OptimizeResponse response = optimizerService.optimize(request);
        return ResponseEntity.ok(response);
    }
}
