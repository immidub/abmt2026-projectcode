package org.eqasim.switzerland.ch_cmdp.mode_choice.utilities.mode_availability;

import com.google.inject.Inject;
import org.eqasim.core.simulation.mode_choice.constraints.OutsideConstraint;
import org.eqasim.switzerland.ch_cmdp.mode_choice.constraints.LoopModesConstraint;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.model.mode_availability.ModeAvailability;
import org.matsim.core.config.Config;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

public class SwissDrtModeAvailability implements ModeAvailability {
    private final ModeAvailability delegate;
    private final boolean drtEnabled;

    @Inject
    public SwissDrtModeAvailability(Config config, SwissDetailedModeAvailability delegate) {
        this(config, (ModeAvailability) delegate);
    }

    SwissDrtModeAvailability(Config config, ModeAvailability delegate) {
        this.delegate = delegate;
        MultiModeDrtConfigGroup drtConfig =
                (MultiModeDrtConfigGroup) config.getModules().get(MultiModeDrtConfigGroup.GROUP_NAME);
        this.drtEnabled = drtConfig != null && drtConfig.modes().anyMatch(TransportMode.drt::equals);
    }

    @Override
    public Collection<String> getAvailableModes(Person person, List<DiscreteModeChoiceTrip> trips) {
        Collection<String> modes = delegate.getAvailableModes(person, trips);
        if (!drtEnabled || Boolean.TRUE.equals(person.getAttributes().getAttribute("isFreight"))
                || trips.stream().noneMatch(SwissDrtModeAvailability::isPassengerTrip)) {
            return modes;
        }

        // For a mixed trip list, the existing trip constraints filter each candidate.
        Collection<String> candidates = new LinkedHashSet<>(modes);
        candidates.add(TransportMode.drt);
        return candidates;
    }

    private static boolean isPassengerTrip(DiscreteModeChoiceTrip trip) {
        String mode = trip.getInitialMode();
        return !OutsideConstraint.OUTSIDE_MODE.equals(mode) && !mode.contains(LoopModesConstraint.LOOP_MODE);
    }
}
