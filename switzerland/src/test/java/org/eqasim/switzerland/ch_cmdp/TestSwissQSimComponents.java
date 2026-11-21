package org.eqasim.switzerland.ch_cmdp;

import java.util.HashSet;

import ch.sbb.matsim.config.SBBTransitConfigGroup;
import ch.sbb.matsim.mobsim.qsim.pt.SBBTransitEngineQSimModule;
import org.junit.Test;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModes;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.mobsim.qsim.components.QSimComponentsConfig;
import org.matsim.core.mobsim.qsim.components.StandardQSimComponentConfigurator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestSwissQSimComponents {
    @Test
    public void baselineRetainsStandardComponentsAndUsesSbbTransit() throws Exception {
        checkComponents(false);
    }

    @Test
    public void drtComponentsAreAddedAlongsideSbbTransit() throws Exception {
        checkComponents(true);
    }

    private static void checkComponents(boolean withDrt) throws Exception {
        var config = ConfigUtils.createConfig(new SBBTransitConfigGroup());
        config.transit().setUseTransit(true);
        if (withDrt) {
            var modes = new MultiModeDrtConfigGroup();
            var drt = new DrtConfigGroup();
            drt.setMode("drt");
            modes.addDrtConfigGroup(drt);
            config.addModule(modes);
            config.addModule(new DvrpConfigGroup());
        }
        var configurator = new SwitzerlandConfigurator(new CommandLine.Builder(new String[0]).build());
        configurator.updateConfig(config);
        var components = new QSimComponentsConfig();
        new StandardQSimComponentConfigurator(config).configure(components);
        var standard = new HashSet<>(components.getActiveComponents());

        configurator.configureComponents(components, config);
        var replaced = new QSimComponentsConfig();
        replaced.addNamedComponent("TransitEngine");
        standard.removeAll(replaced.getActiveComponents());

        assertTrue(components.getActiveComponents().containsAll(standard));
        assertFalse(components.hasNamedComponent("TransitEngine"));
        assertTrue(components.hasNamedComponent(SBBTransitEngineQSimModule.COMPONENT_NAME));
        assertEquals(withDrt, components.hasNamedComponent("DynActivityEngine"));
        assertEquals(withDrt, components.hasNamedComponent("PreplanningEngine"));
        assertEquals(withDrt, components.hasComponent(DvrpModes.mode("drt")));
        assertEquals(components.getActiveComponents().size(), new HashSet<>(components.getActiveComponents()).size());
        assertEquals(1, configurator.getQSimModules(config).stream()
                .filter(SBBTransitEngineQSimModule.class::isInstance).count());
    }
}
