package com.smartload.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

// ── Truck ──────────────────────────────────────────────────────────────────

public record Truck(
        @NotBlank(message = "truck.id must not be blank")
        String id,

        @JsonProperty("max_weight_lbs")
        @Positive(message = "max_weight_lbs must be positive")
        int maxWeightLbs,

        @JsonProperty("max_volume_cuft")
        @Positive(message = "max_volume_cuft must be positive")
        int maxVolumeCuft
) {}
