package com.smartload.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

public record OptimizeRequest(
        @NotNull(message = "truck is required")
        @Valid
        Truck truck,

        @NotNull(message = "orders list is required")
        @Size(max = 22, message = "Too many orders: maximum is 22")
        @Valid
        List<Order> orders
) {}
