package org.eqasim.switzerland.ch_cmdp.mode_choice.drt;

import java.util.List;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.simulation.mode_choice.utilities.UtilityEstimator;
import org.eqasim.switzerland.ch_cmdp.config.SwissDrtBehaviorConfigGroup;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.FareProvider;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.ServiceTimeProvider;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.ServiceTimes;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.TravellerAttributes;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.TravellerAttributesProvider;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.TripAttributes;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.TripAttributesProvider;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.contribs.discrete_mode_choice.modules.config.DiscreteModeChoiceConfigGroup;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;

public final class SwissDrtUtilityEstimator implements UtilityEstimator {
    public static final String NAME = "SwissDrtUtilityEstimator";

    interface PaperUtility {
        double estimate(ServiceTimes times, TripAttributes trip, TravellerAttributes traveller, double fareChf);
    }

    private final SwissDrtParameters parameters;
    private final PaperUtility delegate;
    private final ServiceTimeProvider serviceTimes;
    private final TripAttributesProvider trips;
    private final TravellerAttributesProvider travellers;
    private final FareProvider fares;

    @Inject
    public SwissDrtUtilityEstimator(Config config, Injector injector) {
        this(loadSelection(config), requireProvider(injector, ServiceTimeProvider.class),
                requireProvider(injector, TripAttributesProvider.class),
                requireProvider(injector, TravellerAttributesProvider.class),
                requireProvider(injector, FareProvider.class));
    }

    SwissDrtUtilityEstimator(SwissDrtParameters parameters, ServiceTimeProvider serviceTimes,
            TripAttributesProvider trips, TravellerAttributesProvider travellers, FareProvider fares) {
        this.parameters = parameters;
        this.delegate = switch (parameters) {
            case SwissDrtParameters.Kagho kagho -> new KaghoDrtUtility(kagho);
            case SwissDrtParameters.Erath erath -> new ErathDrtUtility(erath);
        };
        this.serviceTimes = serviceTimes;
        this.trips = trips;
        this.travellers = travellers;
        this.fares = fares;
    }

    private static <T> T requireProvider(Injector injector, Class<T> type) {
        var binding = injector.getExistingBinding(Key.get(type));
        if (binding == null) {
            throw new IllegalStateException(NAME + " requires an explicit " + type.getSimpleName()
                    + " binding.");
        }
        return binding.getProvider().get();
    }

    /** Check after command-line overrides, before loading the scenario or creating a controller. */
    public static void validateConfiguration(Config config) {
        boolean hasBehavior = config.getModules().containsKey(SwissDrtBehaviorConfigGroup.GROUP_NAME);
        boolean usesSwissEstimator = NAME.equals(EqasimConfigGroup.get(config).getEstimators().get(TransportMode.drt));
        if (hasBehavior && !usesSwissEstimator) {
            throw new IllegalArgumentException(SwissDrtBehaviorConfigGroup.GROUP_NAME
                    + " requires the drt estimator to be " + NAME);
        }
        if (usesSwissEstimator) {
            loadSelection(config);
            var behavior = (SwissDrtBehaviorConfigGroup) config.getModules().get(SwissDrtBehaviorConfigGroup.GROUP_NAME);
            behavior.validateInputs();
            var constraints = DiscreteModeChoiceConfigGroup.getOrCreate(config).getTripConstraints();
            for (String required : List.of(SwissDrtInputsModule.INPUT_CONSTRAINT, "DrtWalkConstraint")) {
                if (!constraints.contains(required)) {
                    throw new IllegalArgumentException(NAME + " requires the " + required + " trip constraint");
                }
            }
        }
    }

    static SwissDrtParameters loadSelection(Config config) {
        if (!(config.getModules().get(SwissDrtBehaviorConfigGroup.GROUP_NAME)
                instanceof SwissDrtBehaviorConfigGroup behavior)) {
            throw new IllegalArgumentException(NAME + " requires " + SwissDrtBehaviorConfigGroup.GROUP_NAME);
        }
        var selected = SwissDrtParameters.Specification.parse(behavior.getSpecification());
        String path = behavior.getParametersPath();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Swiss DRT parametersPath is required for " + selected);
        }
        return SwissDrtParameters.load(selected, ConfigGroup.getInputFileURL(config.getContext(), path));
    }

    @Override
    public double estimateUtility(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> elements) {
        var legs = TripStructureUtils.getLegs(elements);
        if (!legs.isEmpty() && legs.stream().allMatch(leg -> TransportMode.walk.equals(leg.getMode()))) {
            // The router can return a walk-only fallback. DrtWalkConstraint discards it after estimation.
            return 0;
        }
        double fare = SwissDrtInputs.nonnegative(fares.fareChf(person, trip, elements), "fare in CHF");
        double published = delegate.estimate(serviceTimes.predict(person, trip, elements),
                trips.describe(trip, elements), travellers.describe(person), fare);
        var transfer = parameters.transfer();
        double utility = transfer.utilityMultiplierToZurich() * published + transfer.ascAdjustmentZurichUtility();
        if (!Double.isFinite(utility)) {
            throw new IllegalArgumentException("Swiss DRT utility is not finite");
        }
        return utility;
    }
}
