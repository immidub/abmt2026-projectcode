package org.eqasim.switzerland.ch_cmdp.mode_choice.drt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.google.inject.Guice;
import com.google.inject.AbstractModule;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtInputs.*;
import org.matsim.core.config.Config;

import org.eqasim.core.components.config.EqasimConfigGroup;
import org.eqasim.core.simulation.mode_choice.utilities.predictors.WalkPredictor;
import org.eqasim.core.simulation.mode_choice.utilities.variables.PersonVariables;
import org.eqasim.switzerland.ch_cmdp.calibration.CmdpVariablesWriter;
import org.eqasim.switzerland.ch_cmdp.mode_choice.parameters.SwissCmdpModeParameters;
import org.eqasim.switzerland.ch_cmdp.mode_choice.utilities.estimators.SwissWalkDetailedUtilityEstimator;
import org.eqasim.switzerland.ch_cmdp.mode_choice.utilities.predictors.SwissPersonPredictor;
import org.eqasim.switzerland.ch_cmdp.mode_choice.utilities.variables.SwissPersonVariables;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contribs.discrete_mode_choice.model.DiscreteModeChoiceTrip;
import org.matsim.core.population.PopulationUtils;
import org.matsim.utils.objectattributes.attributable.AttributesImpl;
import org.eqasim.switzerland.ch_cmdp.SwitzerlandConfigurator;
import org.eqasim.switzerland.ch_cmdp.config.SwissDrtBehaviorConfigGroup;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.ConfigUtils;

import static org.junit.Assert.*;

public class TestSwissDrtBehavior {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final String KAGHO = """
            schemaVersion: 1
            specification: kagho
            coefficients:
              ascUtility: -0.061
              rideUtilityPerMinute: -0.015
              waitUtilityPerMinute: -0.093
              workUtility: -1.938
              age60PlusUtility: -2.6588
              costUtilityPerChf: -0.089
              rideDistanceExponent: 0.115
              costDistanceExponent: -0.221
              costIncomeExponent: -0.817
              referenceDistanceKm: 39
              referenceHouseholdIncomeChfPerMonth: 12260
            transfer:
              utilityMultiplierToZurich: 1.0
              ascAdjustmentZurichUtility: 0.0
              justification: Identity transfer for equation verification and sensitivity testing only.
            """;
    private static final String ERATH = """
            schemaVersion: 1
            specification: erath
            halfFareTreatment: equation47
            coefficients:
              ascUtility: -1.365
              responseUtilityPerMinute: -0.024
              accessEgressUtilityPerMinute: -0.065
              costUtilityPerChf: -0.096
              costDistanceExponent: -0.513
              halfFareUtility: 0.446
            purposes:
              commuting:
                rideUtilityPerMinute: -0.043
                rideDistanceExponent: -0.181
                referenceDistanceKm: 28.4
              leisure:
                rideUtilityPerMinute: -0.041
                rideDistanceExponent: -0.119
                referenceDistanceKm: 30.3
              shopping:
                rideUtilityPerMinute: -0.041
                rideDistanceExponent: -0.119
                referenceDistanceKm: 30.0
              other:
                rideUtilityPerMinute: -0.041
                rideDistanceExponent: -0.119
                referenceDistanceKm: 30.0
            transfer:
              utilityMultiplierToZurich: 1.0
              ascAdjustmentZurichUtility: 0.0
              justification: Identity transfer for equation verification and sensitivity testing only.
            """;

    @Test
    public void xmlAndCommandLineSelectDifferentTypedModelsWithoutChangingOperations() throws Exception {
        write("kagho.yml", KAGHO);
        write("erath.yml", ERATH);
        Path xml = write("config.xml", """
                <!DOCTYPE config SYSTEM "http://www.matsim.org/files/dtd/config_v2.dtd">
                <config>
                  <module name="eqasim:swissDrtBehavior">
                    <param name="specification" value="kagho"/>
                    <param name="parametersPath" value="kagho.yml"/>
                  </module>
                </config>
                """);
        var config = ConfigUtils.loadConfig(xml.toString());
        var cmd = new CommandLine.Builder(new String[]{
                "--config:eqasim:swissDrtBehavior.specification", "erath",
                "--config:eqasim:swissDrtBehavior.parametersPath", "erath.yml"
        }).build();
        new SwitzerlandConfigurator(cmd).updateConfig(config);

        var drt = new DrtConfigGroup();
        drt.setMode("drt");
        drt.setVehiclesFile("fleet.xml");
        drt.setDrtServiceAreaShapeFile("area.shp");
        drt.setOperationalScheme(DrtConfigGroup.OperationalScheme.serviceAreaBased);
        var drtGroup = new MultiModeDrtConfigGroup();
        drtGroup.addDrtConfigGroup(drt);
        config.addModule(drtGroup);
        config.qsim().setEndTime(108000);
        var eqasim = EqasimConfigGroup.get(config);
        eqasim.setEstimator("car", "SwissDetailedCarEstimator");
        eqasim.setEstimator("pt", "SwissDetailedPtEstimator");
        eqasim.setEstimator("bike", "SwissDetailedBikeEstimator");
        eqasim.setEstimator("walk", "SwissDetailedWalkEstimator");
        eqasim.setEstimator("car_passenger", "SwissDetailedCpEstimator");
        eqasim.setEstimator("drt", SwissDrtUtilityEstimator.NAME);
        Map<String, String> bindings = Map.copyOf(eqasim.getEstimators());

        assertTrue(SwissDrtUtilityEstimator.loadSelection(config) instanceof SwissDrtParameters.Kagho);
        SwissDrtUtilityEstimator.validateConfiguration(config);
        double before = scorer(SwissDrtUtilityEstimator.loadSelection(config), TIMES, WORK_TRIP, TRAVELLER, 10)
                .estimateUtility(null, null, List.of());

        double walkBefore = conventionalWalkUtility();
        cmd.applyConfiguration(config);
        assertTrue(SwissDrtUtilityEstimator.loadSelection(config) instanceof SwissDrtParameters.Erath);
        SwissDrtUtilityEstimator.validateConfiguration(config);
        double after = scorer(SwissDrtUtilityEstimator.loadSelection(config), TIMES, WORK_TRIP, TRAVELLER, 10)
                .estimateUtility(null, null, List.of());
        assertNotEquals(before, after, 1e-9);
        assertEquals(walkBefore, conventionalWalkUtility(), 0);
        assertEquals(bindings, eqasim.getEstimators());
        assertEquals("fleet.xml", drt.getVehiclesFile());
        assertEquals("area.shp", drt.getDrtServiceAreaShapeFile());
        assertEquals(DrtConfigGroup.OperationalScheme.serviceAreaBased, drt.getOperationalScheme());
        assertEquals(108000.0, config.qsim().getEndTime().seconds(), 0);
    }

    @Test
    public void conventionalConfigurationDoesNotRequireBehavior() throws Exception {
        var config = ConfigUtils.createConfig();
        var cmd = new CommandLine.Builder(new String[0]).build();
        new SwitzerlandConfigurator(cmd).updateConfig(config);
        var eqasim = EqasimConfigGroup.get(config);
        eqasim.setEstimator("car", "SwissDetailedCarEstimator");
        SwissDrtUtilityEstimator.validateConfiguration(config);
        assertFalse(config.getModules().containsKey(SwissDrtBehaviorConfigGroup.GROUP_NAME));
        assertEquals(Map.of("car", "SwissDetailedCarEstimator"), eqasim.getEstimators());
        eqasim.setEstimator("drt", SwissDrtUtilityEstimator.NAME);
        assertThrows(IllegalArgumentException.class, () -> SwissDrtUtilityEstimator.validateConfiguration(config));
    }

    @Test
    public void rejectsUnknownSelectionAndMismatchedFile() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> SwissDrtParameters.Specification.parse("other"));
        assertInvalid(ERATH, "parameter file declares erath");
        assertInvalid(KAGHO.replace("specification: kagho", "specification: other"), "must be kagho or erath");
    }

    @Test
    public void rejectsMissingUnknownAndNonFiniteCoefficients() throws Exception {
        assertInvalid(KAGHO.replace("  workUtility: -1.938\n", ""), "workUtility must be a finite number");
        assertInvalid(KAGHO.replace("workUtility", "typoUtility"), "Unknown key");
        assertInvalid(KAGHO.replace("workUtility: -1.938", "workUtility: .nan"), "workUtility must be a finite number");
        assertInvalid(KAGHO.replace("workUtility: -1.938", "workUtility: .inf"), "workUtility must be a finite number");
        assertInvalid(KAGHO.replace("workUtility: -1.938", "workUtility: '5'"), "workUtility must be a finite number");
        assertInvalid(KAGHO.replace("workUtility: -1.938", "workUtility: -1.938\n  workUtility: 6"), "duplicate key");
    }

    @Test
    public void rejectsInvalidTransfer() throws Exception {
        assertInvalid(KAGHO.replace("ToZurich: 1.0", "ToZurich: 0"), "utilityMultiplierToZurich must be positive");
        assertInvalid(KAGHO.replace("ToZurich: 1.0", "ToZurich: -2"), "utilityMultiplierToZurich must be positive");
        assertInvalid(KAGHO.replace("ToZurich: 1.0", "ToZurich: .inf"), "utilityMultiplierToZurich must be a finite number");
        assertInvalid(KAGHO.replace("Identity transfer for equation verification and sensitivity testing only.", "''"), "justification must be a nonblank string");
    }

    private static final ServiceTimes TIMES = ServiceTimes.fromSeconds(360, 1200, 360, 240);
    private static final TravellerAttributes TRAVELLER = new TravellerAttributes(60, 6130.0, true);
    private static final TripAttributes WORK_TRIP = TripAttributes.fromMetres(78000, 90000, "work", "home",
            Purpose.commuting);

    @Test
    public void kaghoMatchesHandCalculationAndRespondsToWaitRideFareAndIncome() throws Exception {
        var parameters = load(KAGHO, SwissDrtParameters.Specification.kagho);
        double base = evaluate(parameters, TIMES, WORK_TRIP, TRAVELLER, 10);
        assertEquals(-6.885945249859219, base, 1e-12);
        assertEquals(-0.093, evaluate(parameters, new ServiceTimes(7, 20, 100, 100), WORK_TRIP, TRAVELLER, 10)
                - base, 1e-12);
        assertTrue(evaluate(parameters, new ServiceTimes(6, 21, 6, 4), WORK_TRIP, TRAVELLER, 10) < base);
        assertTrue(evaluate(parameters, TIMES, WORK_TRIP, TRAVELLER, 11) < base);
        assertTrue(evaluate(parameters, TIMES, WORK_TRIP, new TravellerAttributes(60, 12260.0, null), 10) > base);
        assertEquals(2.6588,
                evaluate(parameters, TIMES, WORK_TRIP, new TravellerAttributes(59, 6130.0, null), 10) - base, 1e-12);
        var homeTrip = new TripAttributes(78, 90, "home", "shopping", Purpose.shopping);
        assertEquals(1.938, evaluate(parameters, TIMES, homeTrip, TRAVELLER, 10) - base, 1e-12);
        var returnTrip = new TripAttributes(78, 90, "home", "work_secondary", Purpose.commuting);
        assertEquals(base, evaluate(parameters, TIMES, returnTrip, TRAVELLER, 10), 1e-12);
        assertThrows(IllegalArgumentException.class,
                () -> evaluate(parameters, TIMES, WORK_TRIP, new TravellerAttributes(60, null, null), 10));
        assertThrows(IllegalArgumentException.class,
                () -> evaluate(parameters, TIMES, new TripAttributes(78, 90, null, "work", null), TRAVELLER, 10));
    }

    @Test
    public void erathMatchesHandCalculationAndScoresResponseInsteadOfSeparateWait() throws Exception {
        var parameters = load(ERATH, SwissDrtParameters.Specification.erath);
        var trip = TripAttributes.fromMetres(56800, 60000, "home", "work", Purpose.commuting);
        var unknownCard = new TravellerAttributes(null, null, null);
        double base = evaluate(parameters, TIMES, trip, unknownCard, 10);
        assertEquals(-3.2003317496283294, base, 1e-12);
        assertEquals(base, evaluate(parameters, new ServiceTimes(100, 20, 6, 4), trip, unknownCard, 10), 1e-12);
        assertEquals(-0.024, evaluate(parameters, new ServiceTimes(6, 20, 7, 4), trip, unknownCard, 10)
                - base, 1e-12);
        assertTrue(evaluate(parameters, new ServiceTimes(6, 21, 6, 4), trip, unknownCard, 10) < base);
        assertTrue(evaluate(parameters, TIMES, trip, unknownCard, 11) < base);

        var commuteReference = new TripAttributes(28.4, 40, "home", "work", Purpose.commuting);
        var leisureReference = new TripAttributes(30.3, 40, "home", "leisure", Purpose.leisure);
        assertEquals(-3.589, evaluate(parameters, TIMES, commuteReference, unknownCard, 10), 1e-12);
        assertEquals(-3.549, evaluate(parameters, TIMES, leisureReference, unknownCard, 10), 1e-12);
        assertThrows(IllegalArgumentException.class,
                () -> evaluate(parameters, TIMES, new TripAttributes(28.4, 40, "home", "work", null), unknownCard, 10));

        var tableParameters = load(ERATH.replace("equation47", "table56"), SwissDrtParameters.Specification.erath);
        assertEquals(base + 0.446, evaluate(tableParameters, TIMES, trip, TRAVELLER, 10), 1e-12);
        assertEquals(base, evaluate(tableParameters, TIMES, trip, new TravellerAttributes(null, null, false), 10), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> evaluate(tableParameters, TIMES, trip, unknownCard, 10));
    }

    @Test
    public void validatesTransferAndUtilityInputs() throws Exception {
        var parameters = load(KAGHO, SwissDrtParameters.Specification.kagho);
        var transferred = load(KAGHO.replace("ToZurich: 1.0", "ToZurich: 2.0")
                .replace("ZurichUtility: 0.0", "ZurichUtility: -0.5"), SwissDrtParameters.Specification.kagho);
        double base = evaluate(parameters, TIMES, WORK_TRIP, TRAVELLER, 10);
        assertEquals(2 * base - 0.5, evaluate(transferred, TIMES, WORK_TRIP, TRAVELLER, 10), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> evaluate(parameters, TIMES, WORK_TRIP, TRAVELLER, -1));
        assertThrows(IllegalArgumentException.class, () -> evaluate(parameters, TIMES, WORK_TRIP, TRAVELLER, Double.NaN));
        var overflow = load(KAGHO.replace("ToZurich: 1.0", "ToZurich: 1.e308"),
                SwissDrtParameters.Specification.kagho);
        assertThrows(IllegalArgumentException.class, () -> evaluate(overflow, TIMES, WORK_TRIP, TRAVELLER, 10));
        assertInvalid(KAGHO.replace("referenceDistanceKm: 39", "referenceDistanceKm: 0"),
                "referenceDistanceKm must be positive");
        assertThrows(IllegalArgumentException.class, () -> load(ERATH.replace("equation47", "unknown"),
                SwissDrtParameters.Specification.erath));
    }

    @Test
    public void injectableEstimatorRequiresExplicitProviders() throws Exception {
        var behavior = new SwissDrtBehaviorConfigGroup();
        behavior.setSpecification("kagho");
        behavior.setParametersPath(write("kagho.yml", KAGHO).toString());
        var config = ConfigUtils.createConfig(behavior);
        assertThrows(IllegalStateException.class, () -> new SwissDrtUtilityEstimator(config, Guice.createInjector()));
        var injector = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bind(Config.class).toInstance(config);
                bind(ServiceTimeProvider.class).toInstance((p, t, l) -> TIMES);
                bind(TripAttributesProvider.class).toInstance((t, l) -> WORK_TRIP);
                bind(TravellerAttributesProvider.class).toInstance(p -> TRAVELLER);
                bind(FareProvider.class).toInstance((p, t, l) -> 10);
            }
        });
        assertEquals(-6.885945249859219,
                injector.getInstance(SwissDrtUtilityEstimator.class).estimateUtility(null, null, List.of()), 1e-12);
    }

    private static double conventionalWalkUtility() {
        var person = PopulationUtils.getFactory().createPerson(Id.createPersonId("walk-test"));
        var origin = PopulationUtils.createActivityFromCoord("home", new Coord(0, 0));
        var destination = PopulationUtils.createActivityFromCoord("shopping", new Coord(1200, 0));
        var leg = PopulationUtils.createLeg("walk");
        leg.setTravelTime(900);
        var trip = new DiscreteModeChoiceTrip(origin, destination, "walk", List.of(leg), 0, 0, 0, new AttributesImpl());
        var personPredictor = new SwissPersonPredictor(null) {
            @Override
            protected SwissPersonVariables predict(Person p, DiscreteModeChoiceTrip t,
                    List<? extends PlanElement> elements) {
                return new SwissPersonVariables(new PersonVariables(40), origin.getCoord(), false, false, false,
                        false, false, 0, 1, 6000.0, 1, 0, 0.0, "", false);
            }
        };
        return new SwissWalkDetailedUtilityEstimator(SwissCmdpModeParameters.buildDefault(), new WalkPredictor(),
                personPredictor, new CmdpVariablesWriter()).estimateUtility(person, trip, List.of(leg));
    }

    private SwissDrtParameters load(String yaml, SwissDrtParameters.Specification model) throws Exception {
        return SwissDrtParameters.load(model, write("parameters.yml", yaml).toUri().toURL());
    }

    private static double evaluate(SwissDrtParameters parameters, ServiceTimes times, TripAttributes trip,
            TravellerAttributes traveller, double fare) {
        return scorer(parameters, times, trip, traveller, fare).estimateUtility(null, null, List.of());
    }

    private static SwissDrtUtilityEstimator scorer(SwissDrtParameters parameters, ServiceTimes times,
            TripAttributes trip, TravellerAttributes traveller, double fare) {
        return new SwissDrtUtilityEstimator(parameters, (p, t, l) -> times, (t, l) -> trip,
                p -> traveller, (p, t, l) -> fare);
    }

    private Path write(String filename, String data) throws Exception {
        return Files.writeString(temp.getRoot().toPath().resolve(filename), data);
    }

    private void assertInvalid(String data, String reason) throws Exception {
        var url = write("invalid.yml", data).toUri().toURL();
        var error = assertThrows(IllegalArgumentException.class,
                () -> SwissDrtParameters.load(SwissDrtParameters.Specification.kagho, url));
        assertTrue(error.getMessage(), error.getMessage().contains(reason));
    }
}
