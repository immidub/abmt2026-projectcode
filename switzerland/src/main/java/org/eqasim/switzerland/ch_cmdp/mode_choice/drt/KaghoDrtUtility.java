package org.eqasim.switzerland.ch_cmdp.mode_choice.drt;

import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.ServiceTimes;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.TravellerAttributes;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.TripAttributes;

import static org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.nonnegative;
import static org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.positive;

record KaghoDrtUtility(SwissDrtParameters.Kagho p) implements SwissDrtUtilityEstimator.PaperUtility {
    @Override
    public double estimate(ServiceTimes times, TripAttributes trip, TravellerAttributes traveller, double fareChf) {
        double d = positive(trip.straightLineKm(), "straight-line distance in km") / p.referenceDistanceKm();
        double income = positive(traveller.householdIncomeChfPerMonth(), "household income in CHF/month")
                / p.referenceHouseholdIncomeChfPerMonth();
        Integer age = traveller.ageYears();
        if (age == null || age < 0) {
            throw new IllegalArgumentException("Kagho requires traveller age in years");
        }
        boolean workOrigin = isWork(trip.originActivityType());
        boolean workDestination = isWork(trip.destinationActivityType());
        return p.ascUtility()
                + p.rideUtilityPerMinute() * Math.pow(d, p.rideDistanceExponent())
                        * nonnegative(times.expectedInVehicleMinutes(), "expected in-vehicle minutes")
                + p.waitUtilityPerMinute() * nonnegative(times.expectedPickupWaitMinutes(), "expected pickup wait minutes")
                + (workOrigin || workDestination ? p.workUtility() : 0)
                + (age >= 60 ? p.age60PlusUtility() : 0)
                + p.costUtilityPerChf() * Math.pow(d, p.costDistanceExponent())
                        * Math.pow(income, p.costIncomeExponent()) * fareChf;
    }

    private static boolean isWork(String type) {
        if (type == null || type.isBlank() || "unknown".equals(type)) {
            throw new IllegalArgumentException("Kagho requires both trip endpoint activity types");
        }
        return "work".equals(type) || "work_secondary".equals(type);
    }
}
