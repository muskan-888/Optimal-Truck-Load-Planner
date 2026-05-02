package com.smartload.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class LoadOptimizerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String SAMPLE_BODY = """
            {
              "truck": { "id": "truck-123", "max_weight_lbs": 44000, "max_volume_cuft": 3000 },
              "orders": [
                {
                  "id": "ord-001", "payout_cents": 250000,
                  "weight_lbs": 18000, "volume_cuft": 1200,
                  "origin": "Los Angeles, CA", "destination": "Dallas, TX",
                  "pickup_date": "2025-12-05", "delivery_date": "2025-12-09",
                  "is_hazmat": false
                },
                {
                  "id": "ord-002", "payout_cents": 180000,
                  "weight_lbs": 12000, "volume_cuft": 900,
                  "origin": "Los Angeles, CA", "destination": "Dallas, TX",
                  "pickup_date": "2025-12-04", "delivery_date": "2025-12-10",
                  "is_hazmat": false
                },
                {
                  "id": "ord-003", "payout_cents": 320000,
                  "weight_lbs": 30000, "volume_cuft": 1800,
                  "origin": "Los Angeles, CA", "destination": "Dallas, TX",
                  "pickup_date": "2025-12-06", "delivery_date": "2025-12-08",
                  "is_hazmat": true
                }
              ]
            }
            """;

    @Test
    void optimize_sampleRequest_returns200WithCorrectPayload() throws Exception {
        mockMvc.perform(post("/api/v1/load-optimizer/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SAMPLE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truck_id").value("truck-123"))
                .andExpect(jsonPath("$.total_payout_cents").value(430000))
                .andExpect(jsonPath("$.total_weight_lbs").value(30000))
                .andExpect(jsonPath("$.total_volume_cuft").value(2100))
                .andExpect(jsonPath("$.utilization_weight_percent").value(68.18))
                .andExpect(jsonPath("$.utilization_volume_percent").value(70.0));
    }

    @Test
    void optimize_emptyOrders_returns200WithZeros() throws Exception {
        String body = """
                {
                  "truck": { "id": "t1", "max_weight_lbs": 44000, "max_volume_cuft": 3000 },
                  "orders": []
                }
                """;
        mockMvc.perform(post("/api/v1/load-optimizer/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_payout_cents").value(0))
                .andExpect(jsonPath("$.selected_order_ids").isEmpty());
    }

    @Test
    void optimize_missingTruck_returns400() throws Exception {
        String body = """
                { "orders": [] }
                """;
        mockMvc.perform(post("/api/v1/load-optimizer/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void optimize_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/load-optimizer/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ bad json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void optimize_tooManyOrders_returns413() throws Exception {
        // Build a request with 23 orders
        StringBuilder orders = new StringBuilder();
        for (int i = 0; i < 23; i++) {
            if (i > 0) orders.append(",");
            orders.append("""
                    {
                      "id": "o%d", "payout_cents": 1000,
                      "weight_lbs": 100, "volume_cuft": 10,
                      "origin": "A", "destination": "B",
                      "pickup_date": "2025-12-01", "delivery_date": "2025-12-02",
                      "is_hazmat": false
                    }
                    """.formatted(i));
        }
        String body = """
                {
                  "truck": { "id": "t1", "max_weight_lbs": 99999, "max_volume_cuft": 99999 },
                  "orders": [%s]
                }
                """.formatted(orders);

        mockMvc.perform(post("/api/v1/load-optimizer/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPayloadTooLarge());
    }
}
