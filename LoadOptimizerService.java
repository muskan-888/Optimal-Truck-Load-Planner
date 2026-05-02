package com.smartload.service;

import com.smartload.model.OptimizeRequest;
import com.smartload.model.OptimizeResponse;
import com.smartload.model.Order;
import com.smartload.model.Truck;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * LoadOptimizerService
 *
 * Solves the 2-dimensional 0/1 knapsack (weight + volume) with extra
 * hard constraints:
 *   • Lane compatibility  – all orders must share the same origin→destination
 *   • Hazmat isolation    – hazmat orders cannot ride with non-hazmat orders
 *   • Date validity       – pickup_date <= delivery_date (validated upstream)
 *
 * Algorithm
 * ---------
 * Recursive backtracking with suffix-sum upper-bound pruning.
 *
 * Orders are sorted descending by payout so high-value items are tried first,
 * giving the algorithm a tight upper-bound very early and pruning the vast
 * majority of branches for realistic inputs (n ≤ 22).
 *
 * Worst-case is O(2^n) but average-case is far better in practice.
 * The 4 M state space for n=22 is well within the 800 ms budget.
 *
 * Money: all monetary values use {@code long} (64-bit int cents). Zero floats.
 */
@Service
public class LoadOptimizerService {

    public OptimizeResponse optimize(OptimizeRequest request) {
        Truck truck = request.truck();
        List<Order> orders = request.orders();

        if (orders == null || orders.isEmpty()) {
            return emptyResponse(truck.id());
        }

        // 1. Validate per-order dates
        for (Order o : orders) {
            if (o.pickupDate().compareTo(o.deliveryDate()) > 0) {
                throw new IllegalArgumentException(
                        "Order " + o.id() + ": pickup_date must be <= delivery_date");
            }
        }

        // 2. Pre-filter: drop orders that alone exceed capacity
        List<Order> feasible = orders.stream()
                .filter(o -> o.weightLbs() <= truck.maxWeightLbs()
                          && o.volumeCuft() <= truck.maxVolumeCuft())
                .collect(Collectors.toList());

        if (feasible.isEmpty()) {
            return emptyResponse(truck.id());
        }

        // 3. Group by lane (origin → destination), normalised to lower-case trimmed
        Map<String, List<Order>> byLane = feasible.stream()
                .collect(Collectors.groupingBy(o ->
                        normalise(o.origin()) + "|" + normalise(o.destination())));

        // 4. For each lane solve hazmat and non-hazmat groups independently;
        //    pick the group with the higher payout (hazmat isolation rule).
        //    Then pick the best lane overall.
        long bestPayout = 0;
        List<Order> bestOrders = Collections.emptyList();

        for (List<Order> laneOrders : byLane.values()) {
            List<Order> hazmat    = laneOrders.stream().filter(Order::isHazmat).toList();
            List<Order> nonHazmat = laneOrders.stream().filter(o -> !o.isHazmat()).toList();

            Result hmResult  = solveGroup(hazmat,    truck.maxWeightLbs(), truck.maxVolumeCuft());
            Result nhResult  = solveGroup(nonHazmat, truck.maxWeightLbs(), truck.maxVolumeCuft());

            Result laneResult = hmResult.payout() >= nhResult.payout() ? hmResult : nhResult;

            if (laneResult.payout() > bestPayout) {
                bestPayout  = laneResult.payout();
                bestOrders  = laneResult.orders();
            }
        }

        return buildResponse(truck, bestOrders);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /** Solve a single homogeneous group (all hazmat or all non-hazmat). */
    private Result solveGroup(List<Order> orders, int maxWeight, int maxVolume) {
        if (orders.isEmpty()) return new Result(0L, Collections.emptyList());

        // Sort descending by payout for tight upper-bound pruning
        List<Order> sorted = orders.stream()
                .sorted(Comparator.comparingLong(Order::payoutCents).reversed())
                .collect(Collectors.toList());

        int n = sorted.size();

        // Suffix-sum array: suffixPayout[i] = sum of payouts from index i..n-1
        long[] suffixPayout = new long[n + 1];
        for (int i = n - 1; i >= 0; i--) {
            suffixPayout[i] = suffixPayout[i + 1] + sorted.get(i).payoutCents();
        }

        // Mutable best state
        long[] bestPayout = {0L};
        int[]  bestMask   = {0};

        backtrack(sorted, maxWeight, maxVolume, 0, 0, 0, 0L, 0,
                  suffixPayout, bestPayout, bestMask);

        // Reconstruct selected orders from bitmask
        List<Order> selected = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if ((bestMask[0] & (1 << i)) != 0) {
                selected.add(sorted.get(i));
            }
        }
        return new Result(bestPayout[0], selected);
    }

    /**
     * Recursive backtracking with upper-bound pruning.
     *
     * @param orders        candidate orders (sorted desc by payout)
     * @param maxWeight     truck weight capacity
     * @param maxVolume     truck volume capacity
     * @param idx           current decision index
     * @param curWeight     accumulated weight so far
     * @param curVolume     accumulated volume so far
     * @param curPayout     accumulated payout so far (long, cents)
     * @param curMask       bitmask of chosen indices
     * @param suffixPayout  precomputed suffix sums for pruning
     * @param bestPayout    mutable holder for best payout found
     * @param bestMask      mutable holder for best mask found
     */
    private void backtrack(
            List<Order> orders, int maxWeight, int maxVolume,
            int idx, int curWeight, int curVolume, long curPayout, int curMask,
            long[] suffixPayout, long[] bestPayout, int[] bestMask
    ) {
        // Pruning: even grabbing every remaining order can't beat current best
        if (curPayout + suffixPayout[idx] <= bestPayout[0]) return;

        if (idx == orders.size()) {
            if (curPayout > bestPayout[0]) {
                bestPayout[0] = curPayout;
                bestMask[0]   = curMask;
            }
            return;
        }

        Order o = orders.get(idx);

        // Branch: TAKE this order (if it fits)
        if (curWeight + o.weightLbs() <= maxWeight
                && curVolume + o.volumeCuft() <= maxVolume) {
            backtrack(orders, maxWeight, maxVolume,
                    idx + 1,
                    curWeight + o.weightLbs(),
                    curVolume + o.volumeCuft(),
                    curPayout + o.payoutCents(),
                    curMask | (1 << idx),
                    suffixPayout, bestPayout, bestMask);
        }

        // Branch: SKIP this order
        backtrack(orders, maxWeight, maxVolume,
                idx + 1,
                curWeight, curVolume, curPayout, curMask,
                suffixPayout, bestPayout, bestMask);
    }

    private OptimizeResponse buildResponse(Truck truck, List<Order> selected) {
        long totalPayout = selected.stream().mapToLong(Order::payoutCents).sum();
        int  totalWeight = selected.stream().mapToInt(Order::weightLbs).sum();
        int  totalVolume = selected.stream().mapToInt(Order::volumeCuft).sum();

        double weightPct = round2(100.0 * totalWeight / truck.maxWeightLbs());
        double volumePct = round2(100.0 * totalVolume / truck.maxVolumeCuft());

        return new OptimizeResponse(
                truck.id(),
                selected.stream().map(Order::id).toList(),
                totalPayout,
                totalWeight,
                totalVolume,
                weightPct,
                volumePct
        );
    }

    private OptimizeResponse emptyResponse(String truckId) {
        return new OptimizeResponse(truckId, Collections.emptyList(), 0L, 0, 0, 0.0, 0.0);
    }

    private static String normalise(String s) {
        return s == null ? "" : s.strip().toLowerCase();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Internal value object. */
    private record Result(long payout, List<Order> orders) {}
}
