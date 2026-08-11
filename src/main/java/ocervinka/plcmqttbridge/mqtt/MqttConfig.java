package ocervinka.plcmqttbridge.mqtt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public class MqttConfig {
    private static final String DEFAULT_SCHEME = "tcp";
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT_TCP = 1883;
    private static final int DEFAULT_PORT_TLS = 8883;

    public final String scheme;
    public final String host;
    public final int port;
    /** Not used if null */
    public final String username;
    /** Not used if null */
    public final char[] password;
    public final String clientId;
    public final MqttHaDiscoveryConfig haDiscovery;
    public final boolean watchdogEnabled;
    public final int watchdogIntervalSeconds;
    public final int watchdogTimeoutSeconds;
    public final int watchdogFailureThreshold;
    public final int reconnectCooldownSeconds;
    public final String availabilityTopic;

    public MqttConfig(String scheme, String host, Integer port, String username, char[] password,
                      String clientId, MqttHaDiscoveryConfig haDiscovery) {
        this(scheme, host, port, username, password, clientId, haDiscovery,
                null, null, null, null, null, null);
    }

    @JsonCreator
    public MqttConfig(
            @JsonProperty("scheme") String scheme,
            @JsonProperty("host") String host,
            @JsonProperty("port") Integer port,
            @JsonProperty("username") String username,
            @JsonProperty("password") char[] password,
            @JsonProperty("clientId") String clientId,
            @JsonProperty("ha-discovery") MqttHaDiscoveryConfig haDiscovery,
            @JsonProperty("watchdog-enabled") Boolean watchdogEnabled,
            @JsonProperty("watchdog-interval-seconds") Integer watchdogIntervalSeconds,
            @JsonProperty("watchdog-timeout-seconds") Integer watchdogTimeoutSeconds,
            @JsonProperty("watchdog-failure-threshold") Integer watchdogFailureThreshold,
            @JsonProperty("reconnect-cooldown-seconds") Integer reconnectCooldownSeconds,
            @JsonProperty("availability-topic") String availabilityTopic) {
        this.scheme = scheme == null ? DEFAULT_SCHEME : scheme;
        this.host = host == null ? DEFAULT_HOST : host;
        this.port = port == null ? (this.scheme.equals(DEFAULT_SCHEME) ? DEFAULT_PORT_TCP : DEFAULT_PORT_TLS) : port;
        this.username = username;
        this.password = password;
        this.clientId = clientId == null ? UUID.randomUUID().toString() : clientId;
        this.haDiscovery = haDiscovery != null ? haDiscovery : new MqttHaDiscoveryConfig(null, "homeassistant", null, null, null);
        this.watchdogEnabled = watchdogEnabled == null || watchdogEnabled;
        this.watchdogIntervalSeconds = positiveOrDefault(watchdogIntervalSeconds, 30);
        this.watchdogTimeoutSeconds = positiveOrDefault(watchdogTimeoutSeconds, 10);
        this.watchdogFailureThreshold = positiveOrDefault(watchdogFailureThreshold, 3);
        this.reconnectCooldownSeconds = positiveOrDefault(reconnectCooldownSeconds, 60);
        this.availabilityTopic = availabilityTopic == null
                ? "plccoms-mqtt-bridge/" + sanitizeTopicLevel(this.clientId) + "/mqtt-availability"
                : availabilityTopic;
    }

    private static int positiveOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : Math.max(1, value);
    }

    private static String sanitizeTopicLevel(String value) {
        return value.replace('+', '_').replace('#', '_').replace('/', '_');
    }

    public String getUri() {
        return scheme + "://" + host + ":" + port;
    }
}
