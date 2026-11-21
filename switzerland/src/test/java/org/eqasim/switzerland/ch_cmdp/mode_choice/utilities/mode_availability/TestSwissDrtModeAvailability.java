package org.eqasim.switzerland.ch_cmdp.mode_choice.utilities.mode_availability;

import org.eqasim.core.scenario.cutter.extent.ShapeScenarioExtent;
import org.eqasim.core.simulation.mode_choice.constraints.OutsideConstraint;
import org.eqasim.core.simulation.modes.drt.mode_choice.constraints.DrtServiceAreaConstraint;
import org.eqasim.switzerland.ch_cmdp.mode_choice.constraints.LoopModesConstraint;
import org.junit.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestSwissDrtModeAvailability {
    private final SwissDetailedModeAvailability baseline = new SwissDetailedModeAvailability();
    private final Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId("test"));

    public TestSwissDrtModeAvailability() {
        PersonUtils.setLicence(person, "yes");
        PersonUtils.setCarAvail(person, "always");
        person.getAttributes().putAttribute("bikeAvail", "always");
    }

    @Test
    public void offersDrtAlongsideConventionalModes() {
        assertEquals(Set.of("walk", "pt", "car", "bike", "car_passenger", "drt"),
                enabled().getAvailableModes(person, List.of(trip("walk", 5))));
    }

    @Test
    public void preservesFreightOnlyMode() {
        person.getAttributes().putAttribute("isFreight", true);
        assertEquals(Set.of("truck"), enabled().getAvailableModes(person, List.of(trip("truck", 5))));
    }

    @Test
    public void excludesOutsideAndLoopOnlyTrips() {
        person.getAttributes().putAttribute("outside", true);
        person.getAttributes().putAttribute("hasWalkLoopTrip", true);
        for (String mode : List.of("outside", "walk_loop")) {
            var trips = List.of(trip(mode, 5));
            assertEquals(baseline.getAvailableModes(person, trips), enabled().getAvailableModes(person, trips));
        }
    }

    @Test
    public void mixedTripsRetainDrtForOrdinaryTripAndUsePerTripConstraints() {
        var ordinary = trip("walk", 5);
        var outside = trip("outside", 5);
        var loop = trip("walk_loop", 5);
        assertTrue(enabled().getAvailableModes(person, List.of(ordinary, outside, loop)).contains("drt"));
        assertFalse(new OutsideConstraint().validateBeforeEstimation(outside, "drt", List.of()));
        assertFalse(new LoopModesConstraint().validateBeforeEstimation(loop, "drt", List.of()));
    }

    @Test
    public void serviceAreaIsCheckedByTripConstraint() {
        var polygon = new GeometryFactory().createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10, 0), new Coordinate(10, 10),
                new Coordinate(0, 10), new Coordinate(0, 0)
        });
        var constraint = new DrtServiceAreaConstraint(Map.of("drt", new ShapeScenarioExtent(polygon)));
        var outside = trip("walk", 15);
        assertTrue(enabled().getAvailableModes(person, List.of(outside)).contains("drt"));
        assertFalse(constraint.validateBeforeEstimation(outside, "drt", List.of()));
        assertTrue(constraint.validateBeforeEstimation(outside, "walk", List.of()));
        assertTrue(constraint.validateBeforeEstimation(trip("walk", 5), "drt", List.of()));
    }

    @Test
    public void preservesCarAndBicycleRestrictions() {
        var trips = List.of(trip("walk", 5));
        person.getAttributes().putAttribute("bikeAvail", "never");
        PersonUtils.setLicence(person, "no");
        assertEquals(Set.of("walk", "pt", "car_passenger", "drt"), enabled().getAvailableModes(person, trips));
        PersonUtils.setLicence(person, "yes");
        PersonUtils.setCarAvail(person, "never");
        assertEquals(Set.of("walk", "pt", "car_passenger", "drt"), enabled().getAvailableModes(person, trips));
    }

    @Test
    public void acceptsImmutablePassengerModes() {
        var original = Set.of("walk", "pt");
        var availability = new SwissDrtModeAvailability(drtConfig(), (p, trips) -> original);
        assertEquals(Set.of("walk", "pt", "drt"), availability.getAvailableModes(person, List.of(trip("walk", 5))));
    }

    @Test
    public void delegatesWhenDrtIsDisabled() {
        var trips = List.of(trip("walk", 5));
        var availability = new SwissDrtModeAvailability(ConfigUtils.createConfig(), baseline);
        assertEquals(baseline.getAvailableModes(person, trips), availability.getAvailableModes(person, trips));
    }

    private SwissDrtModeAvailability enabled() {
        return new SwissDrtModeAvailability(drtConfig(), baseline);
    }

    private static Config drtConfig() {
        var modes = new MultiModeDrtConfigGroup();
        var drt = new DrtConfigGroup();
        drt.setMode("drt");
        modes.addDrtConfigGroup(drt);
        return ConfigUtils.createConfig(modes);
    }

    private static DiscreteModeChoiceTrip trip(String mode, double destinationX) {
        var origin = PopulationUtils.createActivityFromCoord("home", new Coord(1, 1));
        var destination = PopulationUtils.createActivityFromCoord("shopping", new Coord(destinationX, 5));
        return new DiscreteModeChoiceTrip(origin, destination, mode, List.of(), 0, 0, 0, new AttributesImpl());
    }
}
