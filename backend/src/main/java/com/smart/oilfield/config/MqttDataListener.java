package com.smart.oilfield.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.oilfield.service.WellDataReceiver;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Slf4j
@Configuration
public class MqttDataListener {

    @Autowired
    private WellDataReceiver wellDataReceiver;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${mqtt.broker}")
    private String broker;

    @Value("${mqtt.client-id}")
    private String clientId;

    @Value("${mqtt.username}")
    private String username;

    @Value("${mqtt.password}")
    private String password;

    @Value("${mqtt.data-topic}")
    private String dataTopic;

    private MqttClient mqttClient;

    @PostConstruct
    public void init() {
        try {
            MqttConnectOptions options = new MqttConnectOptions();
            options.setServerURIs(new String[]{broker});
            options.setUserName(username);
            options.setPassword(password.toCharArray());
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);

            mqttClient = new MqttClient(broker, clientId + "-listener");
            mqttClient.connect(options);

            mqttClient.subscribe(dataTopic, (topic, message) -> {
                String payload = new String(message.getPayload());
                handleIncomingData(payload);
            });

            log.info("MQTT data listener connected successfully, listening on: {}", dataTopic);

        } catch (Exception e) {
            log.error("Failed to start MQTT data listener", e);
        }
    }

    @PreDestroy
    public void destroy() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
                mqttClient.close();
            }
        } catch (Exception e) {
            log.error("Failed to disconnect MQTT listener", e);
        }
    }

    private void handleIncomingData(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String wellType = node.has("wellType") ? node.get("wellType").asText() : "";

            if ("INJECTION".equalsIgnoreCase(wellType) || node.has("waterVolume")) {
                wellDataReceiver.receiveInjectionData(payload);
            } else if ("PRODUCTION".equalsIgnoreCase(wellType) || node.has("oilVolume")) {
                wellDataReceiver.receiveProductionData(payload);
            } else {
                log.warn("Unknown data type received: {}", payload);
            }
        } catch (Exception e) {
            log.error("Failed to handle incoming MQTT data: {}", payload, e);
        }
    }
}
