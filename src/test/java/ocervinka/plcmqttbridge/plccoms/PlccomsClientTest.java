package ocervinka.plcmqttbridge.plccoms;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static List<String> names(Collection<PlccomsVar> vars) {
        List<String> names = new ArrayList<>();
        for (PlccomsVar var : vars) {
            names.add(var.name);
        }
        return names;
    }
}
