package org.eqasim.switzerland.ch_cmdp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.zip.GZIPInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;

import org.junit.Test;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Event regression for the disposable Zürich launcher runs, enabled with -Dswiss.qsim.smokeDirectory. */
public class TestSwissQSimOperations {
    @Test
    public void ptPassengerBoardsAndAlightsWithSbbAlone() throws Exception {
        check("baseline", false);
    }

    @Test
    public void kaghoUsesSbbAndDispatchesExactlyOneSeededRequest() throws Exception {
        check("kagho", true);
    }

    @Test
    public void erathUsesSbbAndDispatchesExactlyOneSeededRequest() throws Exception {
        check("erath", true);
    }

    private static void check(String scenario, boolean drt) throws Exception {
        String smokeDirectory = System.getProperty("swiss.qsim.smokeDirectory");
        assumeTrue("Requires disposable Zürich seed-run outputs", smokeDirectory != null);
        Path root = Path.of(smokeDirectory);
        var log = Files.readAllLines(root.resolve(scenario + "-seed.log"));
        assertTrue(log.stream().anyMatch(line -> line.contains("ITERATION 0 ENDS")));
        assertFalse(log.stream().anyMatch(line -> line.contains("unexpected shutdown")));
        assertEquals(1, log.stream().filter(line -> line.contains("Added DepartureHandler class ")
                && line.endsWith(".SBBTransitQSimEngine")).count());
        assertEquals(drt ? 1 : 0, log.stream().filter(line -> line.contains("Added DepartureHandler class ")
                && line.contains(".dvrp.passenger.")).count());
        assertEquals(drt ? 1 : 0, log.stream().filter(line -> line.contains("Added AgentSource class ")
                && line.endsWith(".VrpAgentSource")).count());

        Path events = root.resolve(scenario + "-seed-output/ITERS/it.0/0.events.xml.gz");
        var transitVehicles = new HashSet<String>();
        var ptBoarded = new HashSet<String>();
        var ptAlighted = new HashSet<String>();
        String submittedRequest = null;
        String pickedVehicle = null;
        int submitted = 0, scheduled = 0, picked = 0, dropped = 0;
        double requestTime = Double.NaN, pickupTime = Double.NaN, dropoffTime = Double.NaN;
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        try (var stream = new GZIPInputStream(Files.newInputStream(events))) {
            var reader = factory.createXMLStreamReader(stream);
            try {
                while (reader.hasNext()) {
                    if (reader.next() != XMLStreamConstants.START_ELEMENT || !"event".equals(reader.getLocalName())) {
                        continue;
                    }
                    String type = reader.getAttributeValue(null, "type");
                    String person = reader.getAttributeValue(null, "person");
                    String vehicle = reader.getAttributeValue(null, "vehicle");
                    if ("TransitDriverStarts".equals(type)) {
                        transitVehicles.add(reader.getAttributeValue(null, "vehicleId"));
                    }
                    if ("1016220".equals(person) && transitVehicles.contains(vehicle)) {
                        if ("PersonEntersVehicle".equals(type)) {
                            ptBoarded.add(vehicle);
                        } else if ("PersonLeavesVehicle".equals(type)) {
                            ptAlighted.add(vehicle);
                        }
                    }
                    if (!"drt".equals(reader.getAttributeValue(null, "mode"))) {
                        continue;
                    }
                    assertFalse("DRT must not teleport", "travelled".equals(type));
                    String request = reader.getAttributeValue(null, "request");
                    double time = Double.parseDouble(reader.getAttributeValue(null, "time"));
                    switch (type) {
                        case "DrtRequest submitted" -> {
                            submitted++;
                            submittedRequest = request;
                            requestTime = time;
                        }
                        case "PassengerRequest scheduled" -> {
                            assertEquals(submittedRequest, request);
                            scheduled++;
                        }
                        case "PassengerRequest rejected" -> fail("Seeded DRT request was rejected");
                        case "passenger picked up" -> {
                            assertEquals(submittedRequest, request);
                            assertEquals("7629460", person);
                            assertNotNull(vehicle);
                            pickedVehicle = vehicle;
                            pickupTime = time;
                            picked++;
                        }
                        case "passenger dropped off" -> {
                            assertEquals(submittedRequest, request);
                            assertEquals(pickedVehicle, vehicle);
                            assertEquals("7629460", person);
                            dropoffTime = time;
                            dropped++;
                        }
                        default -> { }
                    }
                }
            } finally {
                reader.close();
            }
        }
        assertFalse("PT passenger must board a transit vehicle", ptBoarded.isEmpty());
        assertEquals(ptBoarded, ptAlighted);
        int expected = drt ? 1 : 0;
        assertEquals(expected, submitted);
        assertEquals(expected, scheduled);
        assertEquals(expected, picked);
        assertEquals(expected, dropped);
        if (drt) {
            assertTrue(requestTime <= pickupTime && pickupTime < dropoffTime);
        }
    }
}
