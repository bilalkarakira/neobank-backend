package com.bilal.cardnet.shared;

import com.bilal.cardnet.shared.testing.TestDatabase;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SequencesTest {

    @Test
    void stansAreSixDigitsAndRrnsTwelveAndNeverRepeatUnderConcurrency() throws Exception {
        DataSource ds = TestDatabase.fresh();
        Set<String> stans = ConcurrentHashMap.newKeySet();
        Set<String> rrns = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 50; i++) {
                        stans.add(Sequences.nextStan(ds));
                        rrns.add(Sequences.nextRrn(ds));
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        assertThat(stans).hasSize(400).allMatch(s -> s.matches("\\d{6}") && !s.equals("000000"));
        assertThat(rrns).hasSize(400).allMatch(s -> s.matches("\\d{12}"));
    }
}
