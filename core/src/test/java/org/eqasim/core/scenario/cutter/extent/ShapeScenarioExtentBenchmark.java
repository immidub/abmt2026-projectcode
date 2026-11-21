package org.eqasim.core.scenario.cutter.extent;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

import org.geotools.api.data.DataStoreFinder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;

/** Manual benchmark: pass the service-area .shp and an input population .xml.gz. */
public final class ShapeScenarioExtentBenchmark {
    private static final int SAMPLE_SIZE = 512;
    private static final int ORIGINAL_ROUNDS = 3;
    private static final int INDEXED_ROUNDS = 100;

    private record Endpoints(Coord origin, Coord destination) {
        boolean eligible(Predicate<Coord> contains) {
            return contains.test(origin) && contains.test(destination);
        }
    }

    private static Polygon readPolygon(File file) throws Exception {
        var store = DataStoreFinder.getDataStore(Collections.singletonMap("url", file.toURI().toURL()));
        try (var features = store.getFeatureSource(store.getTypeNames()[0]).getFeatures().features()) {
            var geometry = features.next().getDefaultGeometry();
            if (features.hasNext()) {
                throw new IllegalArgumentException("Expected exactly one feature");
            }
            return (Polygon) (geometry instanceof MultiPolygon multi && multi.getNumGeometries() == 1
                    ? multi.getGeometryN(0) : geometry);
        } finally {
            store.dispose();
        }
    }

    private static List<Endpoints> sampleTrips(String populationPath) {
        var samples = new ArrayList<Endpoints>(SAMPLE_SIZE);
        var random = new Random(1234);
        var reader = new StreamingPopulationReader(ScenarioUtils.createScenario(ConfigUtils.createConfig()));
        reader.addAlgorithm(new org.matsim.core.population.algorithms.PersonAlgorithm() {
            private int count;

            @Override
            public void run(org.matsim.api.core.v01.population.Person person) {
                if (Boolean.TRUE.equals(person.getAttributes().getAttribute("isFreight"))) {
                    return;
                }
                for (var trip : TripStructureUtils.getTrips(person.getSelectedPlan())) {
                    Coord origin = trip.getOriginActivity().getCoord();
                    Coord destination = trip.getDestinationActivity().getCoord();
                    if (origin == null || destination == null) {
                        continue;
                    }
                    int position = random.nextInt(++count);
                    if (samples.size() < SAMPLE_SIZE) {
                        samples.add(new Endpoints(origin, destination));
                    } else if (position < SAMPLE_SIZE) {
                        samples.set(position, new Endpoints(origin, destination));
                    }
                }
            }
        });
        reader.readFile(populationPath);
        return samples;
    }

    private static void compare(Predicate<Coord> original, Predicate<Coord> indexed, Coord point) {
        if (original.test(point) != indexed.test(point)) {
            throw new AssertionError("Containment differed at " + point);
        }
    }

    private static void compareRing(Predicate<Coord> original, Predicate<Coord> indexed, LinearRing ring) {
        for (int i = 0; i < ring.getNumPoints() - 1; i += Math.max(1, ring.getNumPoints() / 24)) {
            var vertex = ring.getCoordinateN(i);
            compare(original, indexed, new Coord(vertex.x, vertex.y));
            compare(original, indexed, new Coord(vertex.x - 0.01, vertex.y));
            compare(original, indexed, new Coord(vertex.x + 0.01, vertex.y));
        }
    }

    private static long time(List<Endpoints> sample, Predicate<Coord> predicate, int rounds, long expected) {
        long start = System.nanoTime();
        long eligible = 0;
        for (int i = 0; i < rounds; i++) {
            for (var endpoints : sample) {
                if (endpoints.eligible(predicate)) {
                    eligible++;
                }
            }
        }
        long nanos = System.nanoTime() - start;
        if (eligible != rounds * expected) {
            throw new AssertionError("Timed eligibility count differed");
        }
        return nanos;
    }

    public static void main(String[] args) throws Exception {
        Polygon polygon = readPolygon(new File(args[0]));
        if (!polygon.isValid()) {
            throw new IllegalArgumentException("Benchmark requires a valid polygon");
        }
        var sample = sampleTrips(args[1]);
        if (sample.size() != SAMPLE_SIZE) {
            throw new IllegalArgumentException("Expected " + SAMPLE_SIZE + " sampled trips");
        }
        var pointFactory = polygon.getFactory();
        Predicate<Coord> original = coord ->
                polygon.contains(pointFactory.createPoint(new Coordinate(coord.getX(), coord.getY())));
        Predicate<Coord> indexed = new ShapeScenarioExtent(polygon)::isInside;

        compare(original, indexed, new Coord(polygon.getInteriorPoint().getX(), polygon.getInteriorPoint().getY()));
        compare(original, indexed, new Coord(polygon.getEnvelopeInternal().getMaxX() + 1000,
                polygon.getEnvelopeInternal().getMaxY() + 1000));
        compareRing(original, indexed, polygon.getExteriorRing());
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            var hole = polygon.getInteriorRingN(i);
            var interior = pointFactory.createPolygon((LinearRing) hole.copy()).getInteriorPoint();
            compare(original, indexed, new Coord(interior.getX(), interior.getY()));
            compareRing(original, indexed, hole);
        }

        // The comparison warms up both predicates and initializes the shared index.
        long eligible = 0;
        for (var endpoints : sample) {
            compare(original, indexed, endpoints.origin());
            compare(original, indexed, endpoints.destination());
            if (endpoints.eligible(indexed)) {
                eligible++;
            }
        }
        time(sample, original, 1, eligible);
        time(sample, indexed, 30, eligible);

        double originalMs = time(sample, original, ORIGINAL_ROUNDS, eligible) / (1e6 * ORIGINAL_ROUNDS);
        double indexedMs = time(sample, indexed, INDEXED_ROUNDS, eligible) / (1e6 * INDEXED_ROUNDS);
        System.out.printf("Valid polygon with %d points and %d holes. %d/%d trips eligible, all endpoints agree.%n",
                polygon.getNumPoints(), polygon.getNumInteriorRing(), eligible, sample.size());
        System.out.printf("Original %.3f ms, indexed %.3f ms per %d trips (%.1fx).%n",
                originalMs, indexedMs, sample.size(), originalMs / indexedMs);
    }
}
