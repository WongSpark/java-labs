package com.lab;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;

import static org.junit.jupiter.api.Assertions.*;

class AppTest {

    private final WKTReader reader = new WKTReader();

    @Test
    void testCrossesAntimeridian() throws Exception {
        Geometry normalRect = reader.read("POLYGON((10 10, 20 10, 20 20, 10 20, 10 10))");
        assertFalse(GeometryUtils.crossesAntimeridian(normalRect), "normal rect should not cross");

        Geometry crossRect = reader.read("LINESTRING(170 0, -170 0)");
        assertTrue(GeometryUtils.crossesAntimeridian(crossRect), "line crossing 180 should be detected");
    }

    @Test
    void testSplitAntimeridian() throws Exception {
        Geometry poly = reader.read("POLYGON((170 -10, -170 -10, -170 10, 170 10, 170 -10))");

        assertTrue(GeometryUtils.crossesAntimeridian(poly));

        Geometry split = GeometryUtils.splitAntimeridian(poly);

        assertNotNull(split);
        assertEquals("MultiPolygon", split.getGeometryType());
        assertEquals(2, split.getNumGeometries());
    }
}
