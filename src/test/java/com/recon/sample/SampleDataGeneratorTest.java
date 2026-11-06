package com.recon.sample;

import static org.assertj.core.api.Assertions.assertThat;

import com.recon.reconciliation.domain.BreakType;
import com.recon.sample.SampleDataGenerator.ExpectedBreak;
import com.recon.sample.SampleDataGenerator.SampleData;
import org.junit.jupiter.api.Test;

class SampleDataGeneratorTest {

    @Test
    void sameSeedGivesIdenticalFiles() {
        SampleData first = SampleDataGenerator.generate(7L, "S", 50, 2);
        SampleData second = SampleDataGenerator.generate(7L, "S", 50, 2);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentSeedsGiveDifferentFiles() {
        assertThat(SampleDataGenerator.generate(7L, "S", 50, 2).ledgerCsv())
                .isNotEqualTo(SampleDataGenerator.generate(8L, "S", 50, 2).ledgerCsv());
    }

    @Test
    void plantsEveryBreakType() {
        assertThat(SampleDataGenerator.generate(7L, "S", 0, 1).expectedBreaks())
                .extracting(ExpectedBreak::type)
                .contains(BreakType.values());
    }
}
