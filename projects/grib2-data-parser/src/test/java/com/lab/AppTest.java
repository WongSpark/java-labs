package com.lab;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppTest {

    @Test
    void testAppHasMainMethod() throws NoSuchMethodException {
        assertNotNull(App.class.getMethod("main", String[].class));
    }

    @Test
    void testGrib2ReaderCanBeCreated() {
        Grib2Reader reader = new Grib2Reader();
        assertNotNull(reader);
    }

    @Test
    void testStatisticsTableCanBeCreated() {
        StatisticsTable table = new StatisticsTable();
        assertNotNull(table);
    }
}
