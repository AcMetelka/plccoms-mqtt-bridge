package ocervinka.plcmqttbridge.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

interface MqttClientFactory {
    MqttClientFacade create(String uri, String clientId) throws MqttException;

    MqttClientFactory PAHO = (uri, clientId) ->
            new PahoMqttClientFacade(new MqttClient(uri, clientId, new MemoryPersistence()));
}
