package org.eqasim.switzerland.ch_cmdp.mode_choice.drt;

import java.util.List;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;

/** Inputs to direct DRT utility. Providers supply expectations, with no fallback to dispatch ceilings. */
public final class SwissDrtInputs {
    private SwissDrtInputs() {
    }

    /** Ride time excludes response and access/egress. Response is booking to pickup and is scored once. */
    public record ServiceTimes(double expectedPickupWaitMinutes, double expectedInVehicleMinutes,
            double expectedResponseMinutes, double accessEgressMinutes) {
        public static ServiceTimes fromSeconds(double wait, double ride, double response, double accessEgress) {
            return new ServiceTimes(wait / 60, ride / 60, response / 60, accessEgress / 60);
        }
    }

    public record TripAttributes(double straightLineKm, double routedKm, String originActivityType,
            String destinationActivityType, Purpose purpose) {
        public static TripAttributes fromMetres(double straightLine, double routed, String origin,
                String destination, Purpose purpose) {
            return new TripAttributes(straightLine / 1000, routed / 1000, origin, destination, purpose);
        }
    }

    public enum Purpose {
        commuting, leisure, shopping, other
    }

    /** Null means missing. Household income is total monthly income in CHF, distinct from per-capita income. */
    public record TravellerAttributes(Integer ageYears, Double householdIncomeChfPerMonth, Boolean halfFareCard) {
    }

    @FunctionalInterface
    public interface ServiceTimeProvider {
        ServiceTimes predict(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> itinerary);
    }

    @FunctionalInterface
    public interface TripAttributesProvider {
        TripAttributes describe(DiscreteModeChoiceTrip trip, List<? extends PlanElement> itinerary);
    }

    @FunctionalInterface
    public interface TravellerAttributesProvider {
        TravellerAttributes describe(Person person);
    }

    @FunctionalInterface
    public interface FareProvider {
        /** Return the expected amount paid in CHF, after any actual tariff discounts. */
        double fareChf(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> itinerary);
    }

    static double nonnegative(Double value, String name) {
        if (value == null || !Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Swiss DRT requires finite, nonnegative " + name);
        }
        return value;
    }

    static double positive(Double value, String name) {
        if (value == null || !Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException("Swiss DRT requires finite, positive " + name);
        }
        return value;
    }
}
