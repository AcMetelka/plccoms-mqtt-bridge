package ocervinka.plcmqttbridge.mqtt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class MqttHaDiscoveryConfig {
    public final boolean enabled;
    public final String prefix;
    public final String deviceName;
    public final String deviceFriendlyName;
    public final String deviceModel;

    @JsonCreator
    public MqttHaDiscoveryConfig(
            @JsonProperty("enabled") String enabled,
            @JsonProperty("prefix") String prefix,
            @JsonProperty("device-name") String deviceName,
            @JsonProperty("device-friendly-name") String deviceFriendlyName,
            @JsonProperty("device-model") String deviceModel) {
        this.enabled = Boolean.parseBoolean(enabled);
        this.prefix = prefix != null ? prefix : "homeassistant"; // Default HA prefix
        this.deviceName = deviceName;
        this.deviceFriendlyName = deviceFriendlyName;
        this.deviceModel = deviceModel;
    }
}
