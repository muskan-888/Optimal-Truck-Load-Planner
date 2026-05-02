package com.smartload.service;

import com.smartload.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

class LoadOptimizerServiceTest {

    private LoadOptimizerService service;

    @BeforeEach
    void setUp() {
        service = new LoadOptimizerService();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static OptimizeRequest req(List<Order> orders) {
        return req(orders, 44_000, 3_000);
    }

    private static OptimizeRequest req(List<Order> orders, int maxW, int maxV) {
        return new OptimizeRequest(
                new Truck("truck-test", maxW, maxV),
                orders
        );
    }

    private static Order order(String id, long payoutCents, int weightLbs, int volumeCuft,
                                String origin, String destination, boolean hazmat) {
        return new Order(id, payoutCents, weightLbs, volumeCuft,
                origin, destination,
                "2025-12-01", "2025-12-05", hazmat);
    }

    private static final List<Order> SAMPLE = List.of(
            order("ord-001", 250_000, 18_000, 1_200, "Los Angeles, CA", "Dallas, TX", false),
            order("ord-002", 180_000, 12_000,   900, "Los Angeles, CA", "Dallas, TX", false),
            order("ord-003", 320_000, 30_000, 1_800, "Los Angeles, CA", "Dallas, TX", true)
    );

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void sampleCase_selectsOrd001AndOrd002() {
        var res = service.optimize(req(SAMPLE));

        assertThat(Set.copyOf(res.selectedOrderIds())).isEqualTo(Set.of("ord-001", "ord-002"));
        assertThat(res.totalPayoutCents()).isEqualTo(430_000L);
        assertThat(res.totalWeightLbs()).isEqualTo(30_000);
        assertThat(res.totalVolumeCuft()).isEqualTo(2_100);
        assertThat(res.utilizationWeightPercent()).isEqualTo(68.18);
        assertThat(res.utilizationVolumePercent()).isEqualTo(70.0);
    }

    @Test
    void emptyOrders_returnsZeroResponse() {
        var res = service.optimize(req(Collections.emptyList()));

        assertThat(res.selectedOrderIds()).isEmpty();
        assertThat(res.totalPayoutCents()).isZero();
    }

    @Test
    void allOrdersExceedCapacity_returnsEmpty() {
        var res = service.optimize(req(SAMPLE, 1_000, 100));

        assertThat(res.selectedOrderIds()).isEmpty();
        assertThat(res.totalPayoutCents()).isZero();
    }

    @Test
    void singleOrderFits() {
        var orders = List.of(order("o1", 100_000, 5_000, 200, "NYC", "BOS", false));
        var res = service.optimize(req(orders));

        assertThat(res.selectedOrderIds()).containsExactly("o1");
        assertThat(res.totalPayoutCents()).isEqualTo(100_000L);
    }

    @Test
    void weightConstraintNeverExceeded() {
        var res = service.optimize(req(SAMPLE, 20_000, 3_000));

        assertThat(res.totalWeightLbs()).isLessThanOrEqualTo(20_000);
    }

    @Test
    void volumeConstraintNeverExceeded() {
        var res = service.optimize(req(SAMPLE, 44_000, 1_500));

        assertThat(res.totalVolumeCuft()).isLessThanOrEqualTo(1_500);
    }

    @Test
    void hazmatIsolation_hazmatNotMixedWithNonHazmat() {
        var res = service.optimize(req(SAMPLE));
        var ids = Set.copyOf(res.selectedOrderIds());

        // ord-003 is hazmat; if it's selected, non-hazmat orders must be absent
        if (ids.contains("ord-003")) {
            assertThat(ids).doesNotContain("ord-001", "ord-002");
        }
    }

    @Test
    void hazmatAloneWinsWhenHigherPayout() {
        var orders = List.of(
                order("hz-1", 1_000_000, 10_000, 500, "NYC", "BOS", true),
                order("nh-1",   100_000,  5_000, 200, "NYC", "BOS", false)
        );
        var res = service.optimize(req(orders));

        assertThat(res.selectedOrderIds()).containsExactly("hz-1");
        assertThat(res.totalPayoutCents()).isEqualTo(1_000_000L);
    }

    @Test
    void nonHazmatWinsWhenHigherCombinedPayout() {
        // Two non-hazmat orders together beat one hazmat order
        var orders = List.of(
                order("nh-1", 500_000, 10_000, 500, "NYC", "BOS", false),
                order("nh-2", 500_000, 10_000, 500, "NYC", "BOS", false),
                order("hz-1", 800_000, 12_000, 600, "NYC", "BOS", true)
        );
        var res = service.optimize(req(orders));

        assertThat(Set.copyOf(res.selectedOrderIds())).isEqualTo(Set.of("nh-1", "nh-2"));
        assertThat(res.totalPayoutCents()).isEqualTo(1_000_000L);
    }

    @Test
    void differentLanesNotCombined() {
        var orders = List.of(
                order("la-dal", 200_000, 10_000, 500, "Los Angeles, CA", "Dallas, TX", false),
                order("nyc-bos", 300_000,  8_000, 400, "New York, NY",    "Boston, MA",  false)
        );
        var res = service.optimize(req(orders));

        // Only one lane can win
        assertThat(res.selectedOrderIds()).hasSize(1);
        assertThat(res.selectedOrderIds()).containsExactly("nyc-bos");
    }

    @Test
    void pickupAfterDelivery_throwsIllegalArgument() {
        var order = new Order("bad", 10_000, 1_000, 100,
                "A", "B", "2025-12-10", "2025-12-05", false);
        assertThatThrownBy(() -> service.optimize(req(List.of(order))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pickup_date must be <= delivery_date");
    }

    @Test
    void n22Orders_completesUnder2Seconds() {
        List<Order> orders = IntStream.range(0, 22)
                .mapToObj(i -> order(
                        "ord-" + i,
                        (long) (i + 1) * 10_000,
                        1_000 + i * 200,
                        50 + i * 10,
                        "Los Angeles, CA", "Dallas, TX",
                        false))
                .collect(Collectors.toList());

        long start = System.currentTimeMillis();
        var res = service.optimize(req(orders));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(2_000);
        assertThat(res.totalWeightLbs()).isLessThanOrEqualTo(44_000);
        assertThat(res.totalVolumeCuft()).isLessThanOrEqualTo(3_000);
    }

    @Test
    void moneyStoredAsLong_noOverflow() {
        // 22 orders each at $99,999.99 → 2,199,999.78 total (fits in long fine)
        List<Order> orders = IntStream.range(0, 22)
                .mapToObj(i -> order("o" + i, 9_999_999L, 100, 10, "A", "B", false))
                .collect(Collectors.toList());

        var res = service.optimize(req(orders, 999_999, 999_999));
        assertThat(res.totalPayoutCents()).isGreaterThan(0);
    }
}
