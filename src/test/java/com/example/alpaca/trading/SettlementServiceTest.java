package com.example.alpaca.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SettlementServiceTest {

    @Test
    void fallbackSettleDateIsNextBusinessDay() {
        assertThat(SettlementService.nextBusinessDay(LocalDate.parse("2026-11-17"))).isEqualTo("2026-11-18"); // Tue -> Wed
        assertThat(SettlementService.nextBusinessDay(LocalDate.parse("2026-11-20"))).isEqualTo("2026-11-23"); // Fri -> Mon
        assertThat(SettlementService.nextBusinessDay(LocalDate.parse("2026-11-21"))).isEqualTo("2026-11-23"); // Sat -> Mon
    }
}
