package ocervinka.plcmqttbridge.plccoms;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

public class PlccomsClientTest {

    @Test
    public void listConsumerIsCalledOnceAndItsResultIsSubscribed() {
        AtomicInteger calls = new AtomicInteger();
        PlccomsClient client = new PlccomsClient(vars -> {
            calls.incrementAndGet();
            PlccomsVar first = vars.iterator().next();
            return List.of(new PlccomsVar(first.name, 0.5));
        }, diff -> { });
        List<String> commands = new ArrayList<>();

        client.initializeConnection(commands::add, false);
        commands.clear();
        client.processListLine(commands::add, "TEMPERATURE,REAL");
        client.processListLine(commands::add, "");

        assertEquals(1, calls.get());
        assertEquals(List.of("EN:TEMPERATURE 0.5", "GET:TEMPERATURE"), commands);
    }

    @Test
    public void reconnectStartsWithFreshListVariables() {
        List<List<String>> consumedNames = new ArrayList<>();
        PlccomsClient client = new PlccomsClient(vars -> {
            consumedNames.add(names(vars));
            return vars;
        }, diff -> { });
        List<String> commands = new ArrayList<>();

        client.initializeConnection(commands::add, false);
        client.processListLine(commands::add, "OLD_VAR,BOOL");
        client.processListLine(commands::add, "");

        client.initializeConnection(commands::add, false);
        client.processListLine(commands::add, "NEW_VAR,BOOL");
        client.processListLine(commands::add, "");

        assertEquals(List.of(List.of("OLD_VAR"), List.of("NEW_VAR")), consumedNames);
    }

    @Test
    public void reconnectRebuildsInitializationQueue() {
        PlccomsClient client = new PlccomsClient(vars -> vars, diff -> { });
        List<String> commands = new ArrayList<>();

        client.initializeConnection(commands::add, true);
        client.initializeConnection(commands::add, false);

        assertEquals(List.of("GETINFO:version_plc", "LIST:"), commands);
    }

    @Test
    public void commandMatchingIsCaseInsensitiveButPayloadKeepsCaseAndSeparators() {
        AtomicReference<PlccomsDiff> received = new AtomicReference<>();
        PlccomsClient client = new PlccomsClient(vars -> vars, received::set);

        client.processLine(command -> { }, "dIfF:Text.Value,lowerCase,with:colon", () -> { });

        assertEquals("Text.Value", received.get().name);
        assertEquals("lowerCase,with:colon", received.get().value);
    }

    @Test
    public void listAcceptsPlccoms62FormatAndEmptyListTerminatesIt() {
        AtomicReference<Collection<PlccomsVar>> received = new AtomicReference<>();
        PlccomsClient client = new PlccomsClient(vars -> {
            received.set(new ArrayList<>(vars));
            return List.of();
        }, diff -> { });

        client.processLine(command -> { }, "LiSt:UNICOVERS[0].COVERSTATE,STRING[80]*", () -> { });
        client.processLine(command -> { }, "LIST:", () -> { });

        PlccomsVar variable = received.get().iterator().next();
        assertEquals("UNICOVERS[0].COVERSTATE", variable.name);
        assertEquals("STRING[80]*", variable.type);
    }

    @Test
    public void getInfoPreservesLowercaseValueContainingCommaAndColon() {
        PlccomsClient client = new PlccomsClient(vars -> vars, diff -> { });

        client.processLine(command -> { }, "getinfo:version_plc,v6.2,beta:one", () -> { });

        assertEquals("v6.2,beta:one", client.getPlcVersion());
    }

    @Test
    public void invalidLineDoesNotDispatchPayload() {
        AtomicInteger calls = new AtomicInteger();
        PlccomsClient client = new PlccomsClient(vars -> vars, diff -> calls.incrementAndGet());

        client.processLine(command -> { }, "not a command", () -> calls.incrementAndGet());
        client.processLine(command -> { }, "WARNING:temporary issue", () -> calls.incrementAndGet());

        assertEquals(0, calls.get());
    }

    @Test
    public void changedPublicFileRefreshesListAndSubscriptionsOnce() {
        AtomicInteger listCalls = new AtomicInteger();
        PlccomsClient client = new PlccomsClient(vars -> {
            listCalls.incrementAndGet();
            return vars;
        }, diff -> { });
        List<String> commands = new ArrayList<>();

        client.processLine(commands::add,
                "WARNING: 250 Changed public file: '//RD_NJ_ST.pub'", () -> { });
        client.processLine(commands::add,
                "WARNING: 250 Changed public file: '//RD_NJ_ST.pub'", () -> { });

        assertEquals(List.of("LIST:"), commands);

        client.processLine(commands::add, "LIST:TEMPERATURE,REAL", () -> { });
        client.processLine(commands::add, "LIST:", () -> { });

        assertEquals(1, listCalls.get());
        assertEquals(List.of("LIST:", "EN:TEMPERATURE", "GET:TEMPERATURE"), commands);
    }

    private static List<String> names(Collection<PlccomsVar> vars) {
        List<String> names = new ArrayList<>();
        for (PlccomsVar var : vars) {
            names.add(var.name);
        }
        return names;
    }
}
