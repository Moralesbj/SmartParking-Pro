package com.smartparking.modules.vehicles;

import static org.assertj.core.api.Assertions.*;

import com.smartparking.modules.vehicles.domain.Plate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PlateTest {
    @ParameterizedTest
    @CsvSource({ "abc123,ABC123", "ABC-123,ABC123", " abc 123 ,ABC123", "xyz12d,XYZ12D" })
    void normalizesColombianPlates(String raw, String expected) {
        assertThat(Plate.normalize(raw, "CO")).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "AB123", "ABCD123", "123ABC", "ABC1234", "ÁBC123" })
    void rejectsInvalidPlates(String raw) {
        assertThatThrownBy(() -> Plate.normalize(raw, "CO")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "US", "EC" })
    void otherCountriesAreNotYetEnabled(String country) {
        assertThatThrownBy(() -> Plate.normalize("ABC123", country)).isInstanceOf(IllegalArgumentException.class);
    }
}
