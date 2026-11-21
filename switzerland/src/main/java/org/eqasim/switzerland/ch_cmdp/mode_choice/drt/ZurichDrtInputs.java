package org.eqasim.switzerland.ch_cmdp.mode_choice.drt;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eqasim.switzerland.ch_cmdp.config.SwissDrtBehaviorConfigGroup;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.*;
import org.eqasim.switzerland.ch_cmdp.mode_choice.utilities.estimators.Utils;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.core.config.Config;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.utils.geometry.CoordUtils;
import org.matsim.households.Income;

import static org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.nonnegative;

@Singleton
public final class ZurichDrtInputs implements ServiceTimeProvider, TripAttributesProvider,
        TravellerAttributesProvider, FareProvider {
    private final SwissDrtBehaviorConfigGroup config;
    private final Map<Id<Person>, Double> monthlyIncome;
    private final boolean requiresIncomeAndAge;
    private final boolean requiresHalfFare;

    @Inject
    public ZurichDrtInputs(Config config, Scenario scenario) {
        this.config = (SwissDrtBehaviorConfigGroup) config.getModules().get(SwissDrtBehaviorConfigGroup.GROUP_NAME);
        this.config.validateInputs();
        var parameters = SwissDrtUtilityEstimator.loadSelection(config);
        requiresIncomeAndAge = parameters instanceof SwissDrtParameters.Kagho;
        requiresHalfFare = parameters instanceof SwissDrtParameters.Erath erath
                && erath.halfFareTreatment() == SwissDrtParameters.HalfFareTreatment.table56;
        monthlyIncome = requiresIncomeAndAge ? indexMonthlyIncome(scenario) : Map.of();
    }

    private static Map<Id<Person>, Double> indexMonthlyIncome(Scenario scenario) {
        Map<Id<Person>, Double> incomes = new HashMap<>();
        Set<Id<Person>> seen = new HashSet<>();
        scenario.getHouseholds().getHouseholds().values().forEach(household -> {
            Income income = household.getIncome();
            for (Id<Person> member : household.getMemberIds()) {
                if (!seen.add(member)) {
                    throw new IllegalArgumentException("Duplicate household membership for " + member);
                }
                if (income != null && "CHF".equals(income.getCurrency())
                        && income.getIncomePeriod() == Income.IncomePeriod.month
                        && Double.isFinite(income.getIncome()) && income.getIncome() > 0) {
                    incomes.put(member, income.getIncome());
                }
            }
        });
        return Map.copyOf(incomes);
    }

    @Override
    public TravellerAttributes describe(Person person) {
        var attributes = person.getAttributes();
        Integer age = attributes.getAttribute("age") instanceof Integer value && value >= 0 ? value : null;
        Boolean halfFare = switch (attributes.getAttribute("subscriptions")) {
            case String code -> switch (code) {
                case "HT", "VA+HT" -> true;
                case "GA", "VA", "none" -> false;
                default -> null;
            };
            case null, default -> null;
        };
        return new TravellerAttributes(age, monthlyIncome.get(person.getId()), halfFare);
    }

    @Override
    public TripAttributes describe(DiscreteModeChoiceTrip trip, List<? extends PlanElement> itinerary) {
        var origin = trip.getOriginActivity().getType();
        var destination = trip.getDestinationActivity().getType();
        return TripAttributes.fromMetres(distanceMetres(trip), routedDistanceMetres(itinerary), origin,
                destination, purpose(origin, destination));
    }

    private static double distanceMetres(DiscreteModeChoiceTrip trip) {
        return CoordUtils.calcEuclideanDistance(trip.getOriginActivity().getCoord(),
                trip.getDestinationActivity().getCoord());
    }

    private static Purpose purpose(String origin, String destination) {
        String activity = Utils.HOME.equals(destination) || Utils.HOME_SECONDARY.equals(destination)
                ? origin : destination;
        if (activity == null) {
            return null;
        }
        return switch (activity) {
            case "work", "work_secondary", "education", "education_secondary" -> Purpose.commuting;
            case "leisure" -> Purpose.leisure;
            case "shop" -> Purpose.shopping;
            case "other" -> Purpose.other;
            default -> null;
        };
    }

    boolean hasRequiredAttributes(Person person, DiscreteModeChoiceTrip trip) {
        Activity origin = trip.getOriginActivity();
        Activity destination = trip.getDestinationActivity();
        if (origin.getCoord() == null || destination.getCoord() == null
                || !usableActivity(origin) || !usableActivity(destination)) {
            return false;
        }
        double distance = distanceMetres(trip);
        if (!Double.isFinite(distance) || distance <= 0) {
            return false;
        }
        TravellerAttributes traveller = describe(person);
        if (requiresIncomeAndAge) {
            return traveller.ageYears() != null && traveller.householdIncomeChfPerMonth() != null;
        }
        return purpose(origin.getType(), destination.getType()) != null
                && (!requiresHalfFare || traveller.halfFareCard() != null);
    }

    private static boolean usableActivity(Activity activity) {
        String type = activity.getType();
        return type != null && !type.isBlank() && !Set.of("unknown", "outside", "border").contains(type);
    }

    @Override
    public ServiceTimes predict(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> itinerary) {
        double walkSeconds = 0;
        for (var leg : TripStructureUtils.getLegs(itinerary)) {
            if (TransportMode.walk.equals(leg.getMode())) {
                walkSeconds += nonnegative(leg.getTravelTime().orElse(Double.NaN), "access/egress leg seconds");
            }
        }
        double rideMinutes = nonnegative(drtRoute(itinerary).getDirectRideTime(), "routed direct ride seconds") / 60
                + config.getPoolingDelayMinutes();
        // Booking is assumed to occur at the pickup point after access walking.
        double wait = config.getPickupWaitMinutes();
        return new ServiceTimes(wait, nonnegative(rideMinutes, "expected ride minutes"), wait,
                nonnegative(walkSeconds, "total access/egress seconds") / 60);
    }

    @Override
    public double fareChf(Person person, DiscreteModeChoiceTrip trip, List<? extends PlanElement> itinerary) {
        return routedDistanceMetres(itinerary) / 1000 * config.getFareChfPerRoutedKm();
    }

    private static double routedDistanceMetres(List<? extends PlanElement> itinerary) {
        return nonnegative(drtRoute(itinerary).getDistance(), "routed direct ride metres");
    }

    private static DrtRoute drtRoute(List<? extends PlanElement> itinerary) {
        DrtRoute route = null;
        for (var leg : TripStructureUtils.getLegs(itinerary)) {
            if (TransportMode.walk.equals(leg.getMode())) {
                continue;
            }
            if (route != null || !TransportMode.drt.equals(leg.getMode()) || !(leg.getRoute() instanceof DrtRoute drt)) {
                throw new IllegalArgumentException("Swiss direct DRT requires one routed DRT leg and optional walk legs");
            }
            route = drt;
        }
        if (route == null) {
            throw new IllegalArgumentException("Swiss direct DRT itinerary has no DRT leg");
        }
        return route;
    }
}
