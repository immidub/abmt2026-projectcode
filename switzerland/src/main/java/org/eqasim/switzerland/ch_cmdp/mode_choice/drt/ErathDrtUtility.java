package org.eqasim.switzerland.ch_cmdp.mode_choice.drt;

import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.ServiceTimes;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.TravellerAttributes;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.TripAttributes;

import static org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.nonnegative;
import static org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.positive;

record ErathDrtUtility(SwissDrtParameters.Erath p) implements SwissDrtUtilityEstimator.PaperUtility {
    @Override
    public double estimate(ServiceTimes times, TripAttributes trip, TravellerAttributes traveller, double fareChf) {
        if (trip.purpose() == null) {
            throw new IllegalArgumentException("Erath requires an explicit commuting, leisure, shopping or other purpose");
        }
        var purpose = p.purposes().get(trip.purpose());
        double d = positive(trip.straightLineKm(), "straight-line distance in km") / purpose.referenceDistanceKm();
        return p.ascUtility()
                + purpose.rideUtilityPerMinute() * Math.pow(d, purpose.rideDistanceExponent())
                        * nonnegative(times.expectedInVehicleMinutes(), "expected in-vehicle minutes")
                + p.responseUtilityPerMinute() * nonnegative(times.expectedResponseMinutes(), "expected response minutes")
                + p.accessEgressUtilityPerMinute() * nonnegative(times.accessEgressMinutes(), "access/egress minutes")
                + p.costUtilityPerChf() * Math.pow(d, p.costDistanceExponent()) * fareChf
                + halfFareEffect(traveller);
    }

    private double halfFareEffect(TravellerAttributes traveller) {
        if (p.halfFareTreatment() == SwissDrtParameters.HalfFareTreatment.equation47) {
            return 0;
        }
        if (traveller.halfFareCard() == null) {
            throw new IllegalArgumentException("Erath Table 56 requires explicit half-fare card status");
        }
        return traveller.halfFareCard() ? p.halfFareUtility() : 0;
    }
}
