package ocervinka.plcmqttbridge.mqtt;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.*;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class Mqtt implements MqttGateway {
    private static final Logger LOGGER = LogManager.getLogger();

    private final Object lifecycleLock = new Object();
    private final Map<String, MessageHandler> subscriptions = new ConcurrentHashMap<>();
    private final MqttClientFactory clientFactory;
    private final ScheduledExecutorService watchdogExecutor;
    private final LongSupplier clockMillis;
    private final Supplier<String> nonceSupplier;

    private volatile MqttClientFacade client;
    private volatile boolean connected;
    private volatile boolean closing;
    private MqttConfig config;
    private ScheduledFuture<?> watchdogTask;
    private String pendingProbe;
    private long pendingProbeSince;
    private int consecutiveProbeFailures;
    private long lastRecoveryAttempt = -1;

    public Mqtt() {
        this(MqttClientFactory.PAHO,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "mqtt-watchdog");
                    thread.setDaemon(true);
                    return thread;
                }), System::currentTimeMillis, () -> UUID.randomUUID().toString());
    }

    Mqtt(MqttClientFactory clientFactory, ScheduledExecutorService watchdogExecutor,
         LongSupplier clockMillis, Supplier<String> nonceSupplier) {
        this.clientFactory = clientFactory;
        this.watchdogExecutor = watchdogExecutor;
        this.clockMillis = clockMillis;
        this.nonceSupplier = nonceSupplier;
    }

    public void connect(MqttConfig config) throws MqttException {
        synchronized (lifecycleLock) {
            this.config = config;
            closing = false;
            createAndConnectClient();
            if (config.watchdogEnabled) {
                watchdogTask = watchdogExecutor.scheduleWithFixedDelay(this::runWatchdogSafely,
                        config.watchdogIntervalSeconds, config.watchdogIntervalSeconds, TimeUnit.SECONDS);
            }
        }
    }

    private void createAndConnectClient() throws MqttException {
        MqttClientFacade newClient = clientFactory.create(config.getUri(), config.clientId);
        newClient.setCallback(callbackFor(newClient));
        client = newClient;
        connected = false;

        MqttConnectOptions options = new MqttConnectOptions();
        if (config.username != null) options.setUserName(config.username);
        if (config.password != null) options.setPassword(config.password);
        options.setAutomaticReconnect(true);
        // Subscriptions are deliberately rebuilt on every connection completion.
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setWill(config.availabilityTopic, "offline".getBytes(StandardCharsets.UTF_8), 1, true);
        LOGGER.info("Connecting MQTT client {} to {}", config.clientId, config.getUri());
        newClient.connect(options);
    }

    private MqttCallbackExtended callbackFor(MqttClientFacade callbackClient) {
        return new MqttCallbackExtended() {
            public void connectComplete(boolean reconnect, String serverURI) {
                // Paho invokes this on its callback thread. Synchronous subscribe() waits for
                // SUBACK and must not run here, otherwise a sufficiently large registry can
                // block the very receive path which processes those acknowledgements.
                connected = false;
                watchdogExecutor.execute(() -> onConnectComplete(callbackClient, reconnect, serverURI));
            }
            public void connectionLost(Throwable cause) {
                if (client != callbackClient) return;
                connected = false;
                LOGGER.warn("MQTT connection lost; Paho automatic reconnect will retry", cause);
            }
            public void messageArrived(String topic, MqttMessage message) { }
            public void deliveryComplete(IMqttDeliveryToken token) { }
        };
    }

    private void onConnectComplete(MqttClientFacade callbackClient, boolean reconnect, String serverURI) {
        synchronized (lifecycleLock) {
            if (closing || client != callbackClient) return;
            connected = false;
            pendingProbe = null;
            consecutiveProbeFailures = 0;
            LOGGER.info("MQTT connection complete (reconnect={}, server={}); restoring {} subscription(s)",
                    reconnect, serverURI, subscriptions.size());
            for (Map.Entry<String, MessageHandler> entry : subscriptions.entrySet()) {
                if (!subscribeNow(callbackClient, entry.getKey(), entry.getValue(), 0)) return;
            }
            if (config.watchdogEnabled) {
                if (!subscribeNow(callbackClient, probeTopic(), this::onProbeMessage, 1)) return;
            }
            try {
                publishMessage(callbackClient, config.availabilityTopic, "online", 1, true);
                connected = true;
                LOGGER.info("MQTT lifecycle ready; {} application subscription(s) restored",
                        subscriptions.size());
            } catch (MqttException e) {
                connected = false;
                LOGGER.error("Failed to publish MQTT transport availability", e);
            }
        }
    }

    public void close() throws MqttException {
        synchronized (lifecycleLock) {
            closing = true;
            connected = false;
            if (watchdogTask != null) watchdogTask.cancel(false);
            watchdogExecutor.shutdownNow();
            MqttClientFacade current = client;
            client = null;
            if (current == null) return;
            if (current.isConnected()) {
                try {
                    publishMessage(current, config.availabilityTopic, "offline", 1, true);
                } catch (MqttException e) {
                    LOGGER.warn("Failed to publish graceful MQTT transport shutdown", e);
                }
                current.disconnect();
            }
            current.close();
        }
    }

    public void publish(String topic, String value) throws MqttException {
        MqttClientFacade current = client;
        if (!connected || current == null || !current.isConnected()) {
            MqttException error = new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
            LOGGER.error("Refusing MQTT publish to {} because the transport is disconnected", topic, error);
            throw error;
        }
        publishMessage(current, topic, value, 0, true);
    }

    public void subscribe(Collection<String> topicFilters, MessageHandler handler) throws MqttException {
        for (String topicFilter : topicFilters) {
            subscriptions.put(topicFilter, handler);
            MqttClientFacade current = client;
            if (connected && current != null && current.isConnected()) {
                subscribeNow(current, topicFilter, handler, 0);
            }
        }
    }

    private boolean subscribeNow(MqttClientFacade target, String topic, MessageHandler handler, int qos) {
        try {
            LOGGER.info("Subscribing to MQTT topic {} (QoS {})", topic, qos);
            target.subscribe(topic, qos, (actualTopic, message) -> handler.onMessage(actualTopic, message.getPayload()));
            return true;
        } catch (MqttException e) {
            connected = false;
            LOGGER.error("Failed to subscribe to MQTT topic {}; watchdog will recover the client", topic, e);
            return false;
        }
    }

    private void runWatchdogSafely() {
        try {
            watchdogTick();
        } catch (Throwable e) {
            LOGGER.error("Unexpected MQTT watchdog failure", e);
        }
    }

    void watchdogTick() {
        synchronized (lifecycleLock) {
            if (closing || config == null || !config.watchdogEnabled) return;
            long now = clockMillis.getAsLong();
            MqttClientFacade current = client;
            if (!connected || current == null || !current.isConnected()) {
                recordProbeFailure("transport disconnected", now);
                return;
            }
            if (pendingProbe != null) {
                if (now - pendingProbeSince >= TimeUnit.SECONDS.toMillis(config.watchdogTimeoutSeconds)) {
                    pendingProbe = null;
                    recordProbeFailure("round-trip timeout", now);
                }
                return;
            }
            String nonce = nonceSupplier.get();
            try {
                pendingProbe = nonce;
                pendingProbeSince = now;
                publishMessage(current, probeTopic(), nonce, 1, false);
                LOGGER.debug("MQTT watchdog probe sent");
            } catch (MqttException e) {
                pendingProbe = null;
                LOGGER.warn("MQTT watchdog probe publish failed", e);
                recordProbeFailure("probe publish failed", now);
            }
        }
    }

    private void onProbeMessage(String topic, byte[] payload) {
        synchronized (lifecycleLock) {
            String received = new String(payload, StandardCharsets.UTF_8);
            if (pendingProbe != null && pendingProbe.equals(received)) {
                pendingProbe = null;
                consecutiveProbeFailures = 0;
                LOGGER.debug("MQTT watchdog round-trip succeeded");
            }
        }
    }

    private void recordProbeFailure(String reason, long now) {
        consecutiveProbeFailures++;
        LOGGER.warn("MQTT watchdog failure {}/{}: {}", consecutiveProbeFailures,
                config.watchdogFailureThreshold, reason);
        if (consecutiveProbeFailures < config.watchdogFailureThreshold) return;
        long cooldown = TimeUnit.SECONDS.toMillis(config.reconnectCooldownSeconds);
        if (lastRecoveryAttempt >= 0 && now - lastRecoveryAttempt < cooldown) {
            LOGGER.warn("MQTT watchdog recovery suppressed by reconnect cooldown");
            return;
        }
        lastRecoveryAttempt = now;
        consecutiveProbeFailures = 0;
        recreateClient();
    }

    private void recreateClient() {
        MqttClientFacade oldClient = client;
        client = null;
        connected = false;
        LOGGER.warn("MQTT watchdog recreating client after repeated transport failures");
        if (oldClient != null) {
            try { if (oldClient.isConnected()) oldClient.disconnect(); } catch (MqttException e) {
                LOGGER.warn("Failed to disconnect unhealthy MQTT client", e);
            }
            try { oldClient.close(); } catch (MqttException e) {
                LOGGER.warn("Failed to close unhealthy MQTT client", e);
            }
        }
        try {
            createAndConnectClient();
        } catch (MqttException e) {
            LOGGER.error("MQTT watchdog could not recreate client; next cooldown window will retry", e);
        }
    }

    private String probeTopic() {
        return config.availabilityTopic + "/probe";
    }

    private static void publishMessage(MqttClientFacade target, String topic, String value,
                                       int qos, boolean retained) throws MqttException {
        MqttMessage message = new MqttMessage(value.getBytes(StandardCharsets.UTF_8));
        message.setQos(qos);
        message.setRetained(retained);
        target.publish(topic, message);
    }
}
