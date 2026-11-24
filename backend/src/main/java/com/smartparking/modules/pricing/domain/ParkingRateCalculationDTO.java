package com.smartparking.modules.pricing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Complete breakdown of one liquidation. Produced only by ParkingRateService, the single source of the amount (RN-014).
 *
 * @param billedMinutes     started minutes since entry ("minuto o fracción"); 0 inside the courtesy time
 * @param completedBlocks   blocks of {@code blockMinutes} fully elapsed
 * @param fullRatesReached  blocks whose minute charge reached the full rate (including the current one)
 * @param fullRatesAmount   fullRatesReached × fullRate
 * @param additionalAmount  minute charges of blocks that did not reach the full rate
 * @param previousBlocksAmount value accumulated by the blocks before the current one
 * @param minutesUntilFullRate minutes left until the current block reaches the full rate; null if reached or not reachable in the block
 * @param totalAmount       grossAmount − authorized discounts, never below zero
 * @param balanceDue        totalAmount − previous payments, never below zero
 * @param creditBalance     previous payments above totalAmount (to refund), otherwise zero
 */
public record ParkingRateCalculationDTO(
    String vehicleType, Instant entryAt, Instant settledAt, long staySeconds, long billedMinutes, boolean withinGrace,
    BigDecimal minuteRate, BigDecimal fullRate, int blockMinutes, int graceMinutes,
    long completedBlocks, long fullRatesReached, BigDecimal fullRatesAmount, BigDecimal additionalAmount, BigDecimal previousBlocksAmount,
    long currentBlockNumber, long currentBlockMinutes, BigDecimal currentBlockAmount, boolean currentBlockCapped, Long minutesUntilFullRate,
    long coveredMinutes, BigDecimal grossAmount, BigDecimal authorizedDiscounts, BigDecimal totalAmount,
    BigDecimal previousPayments, BigDecimal balanceDue, BigDecimal creditBalance, List<Block> blocks) {

    /**
     * One billing block. {@code endsAt} is the nominal end (entry + n × block), also for the block in progress.
     *
     * @param elapsedMinutes minutes of the stay inside this block
     * @param coveredMinutes of those, minutes paid by a monthly plan
     */
    public record Block(long number, Instant startsAt, Instant endsAt, long elapsedMinutes, long coveredMinutes, long billableMinutes,
                        BigDecimal amount, boolean fullRateReached) {}
}
