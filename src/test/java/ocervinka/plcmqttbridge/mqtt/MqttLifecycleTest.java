package ocervinka.plcmqttbridge.mqtt;

import org.eclipse.paho.client.mqttv3.*;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class MqttLifecycleTest {
    @Test
    public void restoresRegisteredSubscriptionsAfterReconnect() throws Exception {
        Fixture fixture = new Fixture(config(3, 60));
        fixture.mqtt.subscribe(Collections.singleton("home/light/set"), (topic, payload) -> { });

        assertEquals(1, fixture.client.subscribeCount("home/light/set"));
        fixture.client.callback.connectionLost(new RuntimeException("network"));
        fixture.client.callback.connectComplete(true, "tcp://broker:1883");

        assertEquals(2, fixture.client.subscribeCount("home/light/set"));
        fixture.close();
    }

    @Test
    public void publishWhileDisconnectedFailsExplicitly() throws Exception {
        Fixture fixture = new Fixture(config(3, 60));
        fixture.client.connected = false;
        fixture.client.callback.connectionLost(new RuntimeException("network"));

        try {
            fixture.mqtt.publish("home/light/state", "ON");
            fail("Expected disconnected publish to fail");
        } catch (MqttException e) {
            assertEquals(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED, e.getReasonCode());
        }
        fixture.close();
    }

    @Test
    public void successfulProbeRequiresSubscribedRoundTrip() throws Exception {
        Fixture fixture = new Fixture(config(3, 60));
        fixture.client.echoPublishes = true;

        fixture.mqtt.watchdogTick();
        fixture.mqtt.watchdogTick();

        assertEquals(2, fixture.client.probePublishCount());
        assertEquals(1, fixture.factory.created.size());
        fixture.close();
    }

    @Test
    public void timedOutProbeRecreatesClientAndCooldownLimitsRecovery() throws Exception {
        Fixture fixture = new Fixture(config(1, 10));
        fixture.mqtt.watchdogTick();
        fixture.clock.set(1000);
        fixture.mqtt.watchdogTick();
        assertEquals(2, fixture.factory.created.size());

        FakeClient replacement = fixture.factory.created.get(1);
        replacement.connected = false;
        replacement.callback.connectionLost(new RuntimeException("still down"));
        fixture.clock.set(2000);
        fixture.mqtt.watchdogTick();
        assertEquals(2, fixture.factory.created.size());

        fixture.clock.set(11000);
        fixture.mqtt.watchdogTick();
        assertEquals(3, fixture.factory.created.size());
        fixture.close();
    }

    private static MqttConfig config(int threshold, int cooldownSeconds) {
        return new MqttConfig("tcp", "broker", 1883, null, null, "bridge-test", null,
                true, 3600, 1, threshold, cooldownSeconds, "bridge-test/mqtt-availability");
    }

    private static final class Fixture {
        final AtomicLong clock = new AtomicLong();
        final FakeFactory factory = new FakeFactory();
        final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        final Mqtt mqtt = new Mqtt(factory, executor, clock::get, () -> "nonce");
        final FakeClient client;

        Fixture(MqttConfig config) throws Exception {
            mqtt.connect(config);
            client = factory.created.get(0);
        }

        void close() throws Exception { mqtt.close(); }
    }

    private static final class FakeFactory implements MqttClientFactory {
        final List<FakeClient> created = new ArrayList<>();

        public MqttClientFacade create(String uri, String clientId) {
            FakeClient client = new FakeClient();
            created.add(client);
            return client;
        }
    }

    private static final class FakeClient implements MqttClientFacade {
        MqttCallbackExtended callback;
        boolean connected;
        boolean echoPublishes;
        final Map<String, IMqttMessageListener> listeners = new HashMap<>();
        final Map<String, Integer> subscriptions = new HashMap<>();
        final List<Published> published = new ArrayList<>();

        public void setCallback(MqttCallbackExtended callback) { this.callback = callback; }
        public void connect(MqttConnectOptions options) {
            connected = true;
            callback.connectComplete(false, "tcp://broker:1883");
        }
        public boolean isConnected() { return connected; }
        public void publish(String topic, MqttMessage message) throws MqttException {
            if (!connected) throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
            published.add(new Published(topic, message));
            IMqttMessageListener listener = listeners.get(topic);
            if (echoPublishes && listener != null) {
                try {
                    listener.messageArrived(topic, message);
                } catch (Exception e) {
                    throw new MqttException(e);
                }
            }
        }
        public void subscribe(String topic, int qos, IMqttMessageListener listener) {
            listeners.put(topic, listener);
            subscriptions.put(topic, subscribeCount(topic) + 1);
        }
        public void disconnect() { connected = false; }
        public void close() { connected = false; }
        int subscribeCount(String topic) { return subscriptions.getOrDefault(topic, 0); }
        long probePublishCount() {
            return published.stream().filter(p -> p.topic.endsWith("/probe") && p.message.getQos() == 1
                    && !p.message.isRetained()).count();
        }
    }

    private static final class Published {
        final String topic;
        final MqttMessage message;
        Published(String topic, MqttMessage message) { this.topic = topic; this.message = message; }
    }
}
