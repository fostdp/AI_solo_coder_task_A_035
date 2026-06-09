package com.smart.oilfield.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.oilfield.entity.Alarm;
import com.smart.oilfield.entity.AllocationSuggestion;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class MqttMessageService {

    @Autowired
    private MqttPahoClientFactory mqttClientFactory;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${mqtt.alarm-topic}")
    private String alarmTopic;

    @Value("${mqtt.allocation-topic:oilfield/allocation}")
    private String allocationTopic;

    @Value("${mqtt.client-id}")
    private String clientId;

    @Value("${mqtt.username}")
    private String username;

    @Value("${mqtt.password}")
    private String password;

    @Value("${mqtt.broker}")
    private String broker;

    @Value("${mqtt.message-expiry-seconds:300}")
    private int messageExpirySeconds;

    @Value("${mqtt.max-offline-messages:1000}")
    private int maxOfflineMessages;

    @Value("${mqtt.max-retry-attempts:3}")
    private int maxRetryAttempts;

    private MqttClient mqttClient;
    private final Deque<OfflineMessage> offlineQueue = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicInteger offlineCount = new AtomicInteger(0);
    private final AtomicInteger totalPushed = new AtomicInteger(0);
    private final AtomicInteger totalFailed = new AtomicInteger(0);

    private static class OfflineMessage {
        String topic;
        MqttMessage message;
        long createTime;
        int retryCount;
        Object payload;

        OfflineMessage(String topic, MqttMessage message, Object payload) {
            this.topic = topic;
            this.message = message;
            this.createTime = System.currentTimeMillis();
            this.retryCount = 0;
            this.payload = payload;
        }

        boolean isExpired(int expirySeconds) {
            return (System.currentTimeMillis() - createTime) > (expirySeconds * 1000L);
        }
    }

    @PostConstruct
    public void init() {
        try {
            mqttClient = new MqttClient(broker, clientId + "-publisher", null);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setUserName(username);
            options.setPassword(password.toCharArray());
            options.setCleanSession(false);
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);
            options.setMaxInflight(1000);

            mqttClient.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallbackExtended() {
                @Override
                public void connectionLost(Throwable cause) {
                    isConnected.set(false);
                    log.warn("MQTT publisher connection lost: {}", cause.getMessage());
                }

                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    isConnected.set(true);
                    if (reconnect) {
                        log.info("MQTT publisher reconnected to {}", serverURI);
                        flushOfflineMessages();
                    } else {
                        log.info("MQTT publisher connected successfully to {}", serverURI);
                    }
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                }

                @Override
                public void deliveryComplete(IMqttToken token) {
                }
            });

            mqttClient.connect(options);
            isConnected.set(true);
            log.info("MQTT publisher initialized with message expiry: {}s, max offline: {}, max retry: {}",
                    messageExpirySeconds, maxOfflineMessages, maxRetryAttempts);

        } catch (Exception e) {
            isConnected.set(false);
            log.error("Failed to connect MQTT publisher", e);
        }
    }

    @PreDestroy
    public void destroy() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                if (!offlineQueue.isEmpty()) {
                    log.info("Flushing {} offline messages before shutdown", offlineQueue.size());
                    flushOfflineMessages();
                }
                mqttClient.disconnect(5000);
                mqttClient.close();
            }
        } catch (Exception e) {
            log.error("Failed to disconnect MQTT publisher", e);
        }
    }

    public void pushAlarm(Alarm alarm) {
        try {
            String payload = objectMapper.writeValueAsString(alarm);
            MqttMessage message = new MqttMessage(payload.getBytes());
            message.setQos(1);
            message.setRetained(false);
            message.setExpiry(messageExpirySeconds);

            if (isConnected.get() && ensureConnected()) {
                publishWithCallback(alarmTopic, message, alarm);
            } else {
                queueOfflineMessage(alarmTopic, message, alarm);
            }

        } catch (Exception e) {
            log.error("Failed to prepare alarm for MQTT: {}", alarm.getAlarmId(), e);
            totalFailed.incrementAndGet();
        }
    }

    public void pushAllocationSuggestion(AllocationSuggestion suggestion) {
        try {
            String payload = objectMapper.writeValueAsString(suggestion);
            MqttMessage message = new MqttMessage(payload.getBytes());
            message.setQos(1);
            message.setRetained(false);
            message.setExpiry(messageExpirySeconds);

            if (isConnected.get() && ensureConnected()) {
                publishWithCallback(allocationTopic, message, suggestion);
            } else {
                queueOfflineMessage(allocationTopic, message, suggestion);
            }

        } catch (Exception e) {
            log.error("Failed to prepare allocation suggestion for MQTT: {}", suggestion.getWellId(), e);
            totalFailed.incrementAndGet();
        }
    }

    private void publishWithCallback(String topic, MqttMessage message, Object payload) {
        try {
            mqttClient.publish(topic, message, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    totalPushed.incrementAndGet();
                    if (payload instanceof Alarm) {
                        log.info("Alarm pushed via MQTT: {} - {}", ((Alarm) payload).getAlarmId(),
                                ((Alarm) payload).getAlarmMessage());
                    }
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    log.warn("Publish failed, queueing for retry: {}", exception.getMessage());
                    queueOfflineMessage(topic, message, payload);
                    totalFailed.incrementAndGet();
                }
            });
        } catch (MqttException e) {
            log.warn("Publish exception, queueing for retry: {}", e.getMessage());
            queueOfflineMessage(topic, message, payload);
            totalFailed.incrementAndGet();
        }
    }

    private void queueOfflineMessage(String topic, MqttMessage message, Object payload) {
        OfflineMessage offlineMsg = new OfflineMessage(topic, message, payload);

        while (offlineQueue.size() >= maxOfflineMessages) {
            OfflineMessage removed = offlineQueue.pollFirst();
            if (removed != null) {
                offlineCount.decrementAndGet();
                log.warn("Offline queue full, dropping oldest message from {}",
                        new java.util.Date(removed.createTime));
            }
        }

        offlineQueue.offerLast(offlineMsg);
        int count = offlineCount.incrementAndGet();

        if (count % 10 == 0) {
            log.info("Offline message queue size: {}", count);
        }
    }

    private boolean ensureConnected() {
        if (isConnected.get() && mqttClient.isConnected()) {
            return true;
        }

        try {
            if (!mqttClient.isConnected()) {
                log.info("Attempting to reconnect MQTT publisher...");
                MqttConnectOptions options = new MqttConnectOptions();
                options.setUserName(username);
                options.setPassword(password.toCharArray());
                options.setCleanSession(false);
                options.setAutomaticReconnect(true);
                mqttClient.connect(options);
                isConnected.set(mqttClient.isConnected());

                if (isConnected.get()) {
                    log.info("MQTT publisher reconnected successfully");
                }
            }
        } catch (Exception e) {
            isConnected.set(false);
            log.warn("Failed to reconnect MQTT: {}", e.getMessage());
        }

        return isConnected.get();
    }

    @Scheduled(fixedDelay = 10000)
    public void flushOfflineMessages() {
        if (offlineQueue.isEmpty()) {
            return;
        }

        if (!isConnected.get() && !ensureConnected()) {
            log.debug("Cannot flush offline messages: not connected");
            return;
        }

        log.info("Flushing offline messages, current queue size: {}", offlineQueue.size());

        int flushedCount = 0;
        int expiredCount = 0;
        int maxToFlush = Math.min(100, offlineQueue.size());

        Iterator<OfflineMessage> iterator = offlineQueue.iterator();
        while (iterator.hasNext() && flushedCount < maxToFlush) {
            OfflineMessage msg = iterator.next();

            if (msg.isExpired(messageExpirySeconds)) {
                iterator.remove();
                expiredCount++;
                offlineCount.decrementAndGet();
                continue;
            }

            if (msg.retryCount >= maxRetryAttempts) {
                log.warn("Message exceeded max retry attempts ({}), dropping: {}",
                        maxRetryAttempts, msg.payload);
                iterator.remove();
                offlineCount.decrementAndGet();
                continue;
            }

            try {
                msg.message.setId(0);
                mqttClient.publish(msg.topic, msg.message);
                iterator.remove();
                flushedCount++;
                offlineCount.decrementAndGet();
                msg.retryCount++;
                totalPushed.incrementAndGet();
            } catch (MqttException e) {
                log.warn("Failed to flush offline message (attempt {}): {}",
                        msg.retryCount + 1, e.getMessage());
                msg.retryCount++;
            }
        }

        if (flushedCount > 0 || expiredCount > 0) {
            log.info("Offline flush complete: flushed={}, expired={}, remaining={}",
                    flushedCount, expiredCount, offlineQueue.size());
        }
    }

    @Scheduled(fixedRate = 60000)
    public void cleanExpiredMessages() {
        int removed = 0;
        Iterator<OfflineMessage> iterator = offlineQueue.iterator();
        while (iterator.hasNext()) {
            OfflineMessage msg = iterator.next();
            if (msg.isExpired(messageExpirySeconds)) {
                iterator.remove();
                removed++;
                offlineCount.decrementAndGet();
            }
        }

        if (removed > 0) {
            log.info("Cleaned {} expired offline messages, remaining: {}",
                    removed, offlineQueue.size());
        }
    }

    @Scheduled(fixedRate = 300000)
    public void logStats() {
        log.info("MQTT Publisher Stats - pushed: {}, failed: {}, offline queue: {}, connected: {}",
                totalPushed.get(), totalFailed.get(), offlineCount.get(), isConnected.get());
    }

    public int getOfflineQueueSize() {
        return offlineCount.get();
    }

    public boolean isConnected() {
        return isConnected.get();
    }
}
