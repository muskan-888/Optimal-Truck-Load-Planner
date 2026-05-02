package com.smartload.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public record Order(
        @NotBlank(message = "order.id must not be blank")
        String id,

        @JsonProperty("payout_cents")
        @PositiveOrZero(message = "payout_cents must be >= 0")
        long payoutCents,           // long → never float, 64-bit safe

        @JsonProperty("weight_lbs")
        @PositiveOrZero(message = "weight_lbs must be >= 0")
        int weightLbs,

        @JsonProperty("volume_cuft")
        @PositiveOrZero(message = "volume_cuft must be >= 0")
        int volumeCuft,

        @NotBlank(message = "origin must not be blank")
        String origin,

        @NotBlank(message = "destination must not be blank")
        String destination,

        @NotBlank(message = "pickup_date must not be blank")
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "pickup_date must be YYYY-MM-DD")
        @JsonProperty("pickup_date")
        String pickupDate,

        @NotBlank(message = "delivery_date must not be blank")
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "delivery_date must be YYYY-MM-DD")
        @JsonProperty("delivery_date")
        String deliveryDate,

        @JsonProperty("is_hazmat")
        boolean isHazmat
) {}
