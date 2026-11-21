package org.eqasim.switzerland.ch_cmdp;

import ch.sbb.matsim.config.SBBTransitConfigGroup;
import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import org.eqasim.switzerland.ch_cmdp.mode_choice.drt.SwissDrtUtilityEstimator;
import org.eqasim.core.simulation.mode_choice.utilities.EqasimUtilityEstimator;
import org.eqasim.switzerland.ch_cmdp.StrategyWeightDecay.StrategyWeightDecayModule;
import org.eqasim.switzerland.ch_cmdp.tolls.TollsModule;
import org.eqasim.switzerland.ch_cmdp.utils.link_stats.DetailedLinkStatisticsModule;
import org.eqasim.switzerland.ch_cmdp.transit_dwell.SampledTransitDwellConfigGroup;
import org.eqasim.switzerland.ch_cmdp.transit_dwell.SampledTransitDwellQSimModule;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.CommandLine.ConfigurationException;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.IOException;


public class RunSimulation {
	@SuppressWarnings("deprecation")
	static public void main(String[] args) throws ConfigurationException, IOException {
		// set preventwaitingtoentertraffic to y if you want to to prevent that waiting traffic has to wait for space in the link buffer
		// this is especially important to avoid high waiting times when we cutout scenarios from a larger scenario.
		CommandLine cmd = new CommandLine.Builder(args) //
				.requireOptions("config-path") //
				.allowOptions("validate-drt-setup") //
				.allowPrefixes("mode-parameter", "cost-parameter", "preventwaitingtoentertraffic", "samplingRateForPT") //
				.build();

		SwitzerlandConfigurator configurator = new SwitzerlandConfigurator(cmd);
		Config config = ConfigUtils.loadConfig(cmd.getOptionStrict("config-path"),
				new SBBTransitConfigGroup(), new SampledTransitDwellConfigGroup());
		configurator.updateConfig(config);
		configurator.configure(config);
		cmd.applyConfiguration(config);
		SwissDrtUtilityEstimator.validateConfiguration(config);

		if (cmd.hasOption("preventwaitingtoentertraffic")) {
			if (cmd.getOption("preventwaitingtoentertraffic").get().equals("y")) {
				((QSimConfigGroup) config.getModules().get(QSimConfigGroup.GROUP_NAME))
						.setPcuThresholdForFlowCapacityEasing(1.0);
			}
		}

		Scenario scenario = ScenarioUtils.createScenario(config);

		SwissRailRaptorConfigGroup srrConfigGroup = (SwissRailRaptorConfigGroup) config.getModules().getOrDefault("swissRailRaptor", null);
		System.out.println("Found the following transfer penalties: " + srrConfigGroup.getModeToModeTransferPenaltyParameterSets());

		configurator.configureScenario(scenario);
		ScenarioUtils.loadScenario(scenario);
		configurator.adjustScenario(scenario);
		configurator.adjustPTpcu(scenario);
		Controler controller = new Controler(scenario);
		configurator.configureController(controller);
		controller.addOverridingModule(new PTPassengerCountsModule());
        controller.addOverridingModule(new PTLinkVolumesModule());
		controller.addOverridingModule(new StrategyWeightDecayModule());
		controller.addOverridingModule(new TollsModule());
		controller.addOverridingModule(new DetailedLinkStatisticsModule());

		controller.addOverridingQSimModule(new SampledTransitDwellQSimModule());
        if (cmd.hasOption("validate-drt-setup")) {
            controller.getInjector().getInstance(EqasimUtilityEstimator.class);
            System.out.println("CMDP routed utility estimator constructed. No simulation iterations were started.");
            return;
        }
		// save updated config before running the simulation
		ConfigUtils.writeConfig(config, "run_config.xml");
		controller.run();
	}
}
