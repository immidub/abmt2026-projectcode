package org.eqasim.switzerland.ch_cmdp.mode_choice.drt;

import java.util.Collection;
import java.util.List;

import com.google.inject.Inject;
import org.eqasim.core.simulation.mode_choice.AbstractEqasimExtension;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.*;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.constraints.AbstractTripConstraint;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripConstraint;
import org.matsim.contribs.discrete_mode_choice.model.trip_based.TripConstraintFactory;

public final class SwissDrtInputsModule extends AbstractEqasimExtension {
    public static final String INPUT_CONSTRAINT = "SwissDrtInputConstraint";

    @Override
    protected void installEqasimExtension() {
        bind(ZurichDrtInputs.class);
        bind(ServiceTimeProvider.class).to(ZurichDrtInputs.class);
        bind(TripAttributesProvider.class).to(ZurichDrtInputs.class);
        bind(TravellerAttributesProvider.class).to(ZurichDrtInputs.class);
        bind(FareProvider.class).to(ZurichDrtInputs.class);
        bindTripConstraintFactory(INPUT_CONSTRAINT).to(InputConstraintFactory.class);
    }

    public static final class InputConstraintFactory implements TripConstraintFactory {
        private final ZurichDrtInputs inputs;

        @Inject
        public InputConstraintFactory(ZurichDrtInputs inputs) {
            this.inputs = inputs;
        }

        @Override
        public TripConstraint createConstraint(Person person, List<DiscreteModeChoiceTrip> trips,
                Collection<String> availableModes) {
            return new AbstractTripConstraint() {
                @Override
                public boolean validateBeforeEstimation(DiscreteModeChoiceTrip trip, String mode,
                        List<String> previousModes) {
                    return !TransportMode.drt.equals(mode) || inputs.hasRequiredAttributes(person, trip);
                }
            };
        }
    }
}
