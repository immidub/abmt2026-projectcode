package org.eqasim.core.scenario.cutter.extent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.eqasim.core.simulation.modes.drt.mode_choice.constraints.DrtServiceAreaConstraint;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.WKTReader;
import org.matsim.api.core.v01.Coord;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.core.population.PopulationUtils;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestShapeScenarioExtent {
    private static final List<Coord> POINTS = List.of(
            new Coord(2, 2), new Coord(11, 2), new Coord(0, 2), new Coord(0, 0),
            new Coord(5, 5), new Coord(4, 5), new Coord(4, 4),
            new Coord(-1e-9, 2), new Coord(1e-9, 2),
            new Coord(4 - 1e-9, 5), new Coord(4 + 1e-9, 5));

    @Test
    public void matchesOriginalContainmentForInteriorExteriorBoundaryAndHole() throws Exception {
        Polygon polygon = polygon();
        ShapeScenarioExtent extent = new ShapeScenarioExtent(polygon);
        List<Boolean> expected = List.of(true, false, false, false, false, false, false,
                false, true, true, false);
        assertEquals(expected, POINTS.stream().map(point -> original(polygon, point)).toList());
        assertEquals(expected, POINTS.stream().map(extent::isInside).toList());
    }

    @Test
    public void checksBothDrtEndpointsAndLeavesOtherModesAlone() throws Exception {
        var constraint = new DrtServiceAreaConstraint(Map.of("drt", new ShapeScenarioExtent(polygon())));
        Coord inside = POINTS.get(0);
        Coord outside = POINTS.get(1);
        assertTrue(constraint.validateBeforeEstimation(trip(inside, inside), "drt", List.of()));
        assertFalse(constraint.validateBeforeEstimation(trip(inside, outside), "drt", List.of()));
        assertFalse(constraint.validateBeforeEstimation(trip(outside, inside), "drt", List.of()));
        assertTrue(constraint.validateBeforeEstimation(trip(outside, inside), "walk", List.of()));
    }

    @Test(timeout = 10000)
    public void concurrentFirstAndSubsequentQueriesAgreeWithOriginal() throws Exception {
        Polygon polygon = polygon();
        List<Boolean> expected = POINTS.stream().map(point -> original(polygon, point)).toList();
        var extent = new ShapeScenarioExtent(polygon);
        var ready = new CountDownLatch(8);
        List<Callable<Void>> workers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            workers.add(() -> {
                ready.countDown();
                ready.await();
                for (int repeat = 0; repeat < 500; repeat++) {
                    for (int point = 0; point < POINTS.size(); point++) {
                        assertEquals(expected.get(point), extent.isInside(POINTS.get(point)));
                    }
                }
                return null;
            });
        }
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var future : executor.invokeAll(workers)) {
                future.get();
            }
        }
    }

    private static Polygon polygon() throws Exception {
        return (Polygon) new WKTReader().read(
                "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0), (4 4, 6 4, 6 6, 4 6, 4 4))");
    }

    static boolean original(Polygon polygon, Coord coord) {
        return polygon.contains(polygon.getFactory().createPoint(new Coordinate(coord.getX(), coord.getY())));
    }

    private static DiscreteModeChoiceTrip trip(Coord origin, Coord destination) {
        return new DiscreteModeChoiceTrip(PopulationUtils.createActivityFromCoord("home", origin),
                PopulationUtils.createActivityFromCoord("work", destination), "walk", List.of(),
                0, 0, 0, new AttributesImpl());
    }
}
