package ocervinka.plcmqttbridge.mqtt;

import java.util.Collection;

public interface MqttGateway {
    @FunctionalInterface
    interface MessageHandler {
        void onMessage(String topic, byte[] payload);
    }

    void connect(MqttConfig config) throws Exception;
    void close() throws Exception;
    default void publish(String topic, String value) throws Exception {
        publish(topic, value, true);
    }
    void publish(String topic, String value, boolean retained) throws Exception;
    void subscribe(Collection<String> topicFilters, MessageHandler handler) throws Exception;
}
