package com.smart.oilfield.service;

import com.smart.oilfield.entity.Alarm;
import com.smart.oilfield.entity.AllocationSuggestion;
import com.smart.oilfield.entity.ProductionData;
import com.smart.oilfield.entity.WaterInjectionData;
import com.smart.oilfield.entity.Well;
import com.smart.oilfield.event.AlarmTriggeredEvent;
import com.smart.oilfield.event.AllocationOptimizationCompletedEvent;
import com.smart.oilfield.event.WellDataReceivedEvent;
import com.smart.oilfield.repository.AlarmRepository;
import com.smart.oilfield.repository.ProductionDataRepository;
import com.smart.oilfield.repository.WaterInjectionDataRepository;
import com.smart.oilfield.repository.WellRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AlarmPublisher {

    @Autowired
    private AlarmRepository alarmRepository;

    @Autowired
    private WellRepository wellRepository;

    @Autowired
    private ProductionDataRepository productionDataRepository;

    @Autowired
    private WaterInjectionDataRepository injectionDataRepository;

    @Autowired
    private MqttMessageService mqttMessageService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Value("${alarm.water-cut-rise-threshold:5.0}")
    private Double waterCutRiseThreshold;

    @Value("${alarm.pressure-threshold-ratio:0.8}")
    private Double pressureThresholdRatio;

    @EventListener
    @Async
    public void onDataReceived(WellDataReceivedEvent event) {
        log.debug("Received WellDataReceivedEvent for alarm check, type={}", event.getWellType());

        if (event.hasProductionData()) {
            checkRealTimeWaterCutAlarms(event.getProductionDataList());
        }

        if (event.hasInjectionData()) {
            checkRealTimePressureAlarms(event.getInjectionDataList());
        }
    }

    @EventListener
    @Async
    public void onAllocationOptimizationCompleted(AllocationOptimizationCompletedEvent event) {
        log.info("Received AllocationOptimizationCompletedEvent for block: {}, suggestions: {}",
                event.getBlockName(), event.getSuggestions().size());

        pushAllocationSuggestions(event.getSuggestions());
    }

    @Scheduled(cron = "${alarm.check-schedule:0 0 1 * * ?}")
    public void scheduledAlarmCheck() {
        log.info("Starting scheduled alarm check...");
        List<Alarm> level1Alarms = checkWaterCutAlarms();
        List<Alarm> level2Alarms = checkPressureAlarms();
        pushUnsentAlarms();
        log.info("Scheduled alarm check completed, level1: {}, level2: {}",
                level1Alarms.size(), level2Alarms.size());
    }

    @Transactional
    public List<Alarm> checkWaterCutAlarms() {
        log.info("Checking water cut alarms for all production wells...");
        List<Alarm> newAlarms = new ArrayList<>();
        List<Well> productionWells = wellRepository.findActiveWellsByType("PRODUCTION");
        LocalDate today = LocalDate.now();
        LocalDate oneMonthAgo = today.minusMonths(1);

        for (Well well : productionWells) {
            try {
                ProductionData latestData = productionDataRepository.findLatestByWellId(well.getWellId());
                if (latestData == null) continue;

                List<ProductionData> monthAgoData = productionDataRepository
                        .findByWellIdAndReportDateBetweenOrderByReportDate(
                                well.getWellId(), oneMonthAgo, oneMonthAgo.plusDays(3));

                if (monthAgoData.isEmpty()) continue;

                ProductionData oldData = monthAgoData.get(0);
                double waterCutRise = latestData.getWaterCut() - oldData.getWaterCut();

                if (waterCutRise > waterCutRiseThreshold) {
                    Alarm alarm = createWaterCutAlarm(well, latestData.getWaterCut(), oldData.getWaterCut());
                    alarm = alarmRepository.save(alarm);
                    newAlarms.add(alarm);
                    log.warn("Water cut alarm created for well: {}, rise: {}%", well.getWellId(), waterCutRise);
                }
            } catch (Exception e) {
                log.error("Error checking water cut alarm for well: {}", well.getWellId(), e);
            }
        }

        if (!newAlarms.isEmpty()) {
            publishAlarmTriggeredEvent("LEVEL_1", newAlarms);
            pushAlarms(newAlarms);
        }

        return newAlarms;
    }

    @Transactional
    public List<Alarm> checkPressureAlarms() {
        log.info("Checking pressure alarms for all injection wells...");
        List<Alarm> newAlarms = new ArrayList<>();
        List<Well> injectionWells = wellRepository.findActiveWellsByType("INJECTION");

        for (Well well : injectionWells) {
            try {
                if (well.getDesignPressure() == null) continue;

                WaterInjectionData latestData = injectionDataRepository.findLatestByWellId(well.getWellId());
                if (latestData == null) continue;

                double threshold = well.getDesignPressure() * pressureThresholdRatio;

                if (latestData.getInjectionPressure() > threshold) {
                    Alarm alarm = createPressureAlarm(well, latestData.getInjectionPressure(), threshold);
                    alarm = alarmRepository.save(alarm);
                    newAlarms.add(alarm);
                    log.warn("Pressure alarm created for well: {}, pressure: {} MPa", well.getWellId(), latestData.getInjectionPressure());
                }
            } catch (Exception e) {
                log.error("Error checking pressure alarm for well: {}", well.getWellId(), e);
            }
        }

        if (!newAlarms.isEmpty()) {
            publishAlarmTriggeredEvent("LEVEL_2", newAlarms);
            pushAlarms(newAlarms);
        }

        return newAlarms;
    }

    private void checkRealTimeWaterCutAlarms(List<ProductionData> dataList) {
        log.debug("Checking real-time water cut alarms for {} records", dataList.size());
        List<Alarm> newAlarms = new ArrayList<>();
        LocalDate oneMonthAgo = LocalDate.now().minusMonths(1);

        for (ProductionData latestData : dataList) {
            try {
                Well well = wellRepository.findById(latestData.getWellId()).orElse(null);
                if (well == null || !"ACTIVE".equals(well.getStatus())) continue;

                List<ProductionData> monthAgoData = productionDataRepository
                        .findByWellIdAndReportDateBetweenOrderByReportDate(
                                well.getWellId(), oneMonthAgo, oneMonthAgo.plusDays(3));

                if (monthAgoData.isEmpty()) continue;

                ProductionData oldData = monthAgoData.get(0);
                double waterCutRise = latestData.getWaterCut() - oldData.getWaterCut();

                if (waterCutRise > waterCutRiseThreshold) {
                    boolean existingAlarm = alarmRepository.existsByWellIdAndAlarmTypeAndAlarmTimeAfter(
                            well.getWellId(), "WATER_CUT_RISE", LocalDateTime.now().minusHours(12));
                    if (existingAlarm) continue;

                    Alarm alarm = createWaterCutAlarm(well, latestData.getWaterCut(), oldData.getWaterCut());
                    alarm = alarmRepository.save(alarm);
                    newAlarms.add(alarm);
                    log.warn("Real-time water cut alarm created for well: {}, rise: {}%", well.getWellId(), waterCutRise);
                }
            } catch (Exception e) {
                log.error("Error checking real-time water cut alarm for well: {}", latestData.getWellId(), e);
            }
        }

        if (!newAlarms.isEmpty()) {
            publishAlarmTriggeredEvent("LEVEL_1", newAlarms);
            pushAlarms(newAlarms);
        }
    }

    private void checkRealTimePressureAlarms(List<WaterInjectionData> dataList) {
        log.debug("Checking real-time pressure alarms for {} records", dataList.size());
        List<Alarm> newAlarms = new ArrayList<>();

        for (WaterInjectionData latestData : dataList) {
            try {
                Well well = wellRepository.findById(latestData.getWellId()).orElse(null);
                if (well == null || !"ACTIVE".equals(well.getStatus()) || well.getDesignPressure() == null) continue;

                double threshold = well.getDesignPressure() * pressureThresholdRatio;

                if (latestData.getInjectionPressure() > threshold) {
                    boolean existingAlarm = alarmRepository.existsByWellIdAndAlarmTypeAndAlarmTimeAfter(
                            well.getWellId(), "PRESSURE_ANOMALY", LocalDateTime.now().minusHours(12));
                    if (existingAlarm) continue;

                    Alarm alarm = createPressureAlarm(well, latestData.getInjectionPressure(), threshold);
                    alarm = alarmRepository.save(alarm);
                    newAlarms.add(alarm);
                    log.warn("Real-time pressure alarm created for well: {}, pressure: {} MPa", well.getWellId(), latestData.getInjectionPressure());
                }
            } catch (Exception e) {
                log.error("Error checking real-time pressure alarm for well: {}", latestData.getWellId(), e);
            }
        }

        if (!newAlarms.isEmpty()) {
            publishAlarmTriggeredEvent("LEVEL_2", newAlarms);
            pushAlarms(newAlarms);
        }
    }

    private Alarm createWaterCutAlarm(Well well, double currentWaterCut, double previousWaterCut) {
        Alarm alarm = new Alarm();
        alarm.setAlarmId(UUID.randomUUID().toString());
        alarm.setWellId(well.getWellId());
        alarm.setAlarmLevel("LEVEL_1");
        alarm.setAlarmType("WATER_CUT_RISE");
        alarm.setAlarmMessage(String.format(
                "%s 含水率月上升超过阈值，当前: %.2f%%, 上月同期: %.2f%%, 上升: %.2f%%",
                well.getWellName(), currentWaterCut, previousWaterCut, currentWaterCut - previousWaterCut));
        alarm.setAlarmValue(currentWaterCut - previousWaterCut);
        alarm.setThresholdValue(waterCutRiseThreshold);
        alarm.setAlarmTime(LocalDateTime.now());
        alarm.setIsPushed(false);
        alarm.setIsAcknowledged(false);
        return alarm;
    }

    private Alarm createPressureAlarm(Well well, double currentPressure, double threshold) {
        Alarm alarm = new Alarm();
        alarm.setAlarmId(UUID.randomUUID().toString());
        alarm.setWellId(well.getWellId());
        alarm.setAlarmLevel("LEVEL_2");
        alarm.setAlarmType("PRESSURE_ANOMALY");
        alarm.setAlarmMessage(String.format(
                "%s 注水压力异常升高，当前: %.2f MPa, 阈值: %.2f MPa (设计压力的 %d%%)",
                well.getWellName(), currentPressure, threshold, (int) (pressureThresholdRatio * 100)));
        alarm.setAlarmValue(currentPressure);
        alarm.setThresholdValue(threshold);
        alarm.setAlarmTime(LocalDateTime.now());
        alarm.setIsPushed(false);
        alarm.setIsAcknowledged(false);
        return alarm;
    }

    private void pushAlarms(List<Alarm> alarms) {
        for (Alarm alarm : alarms) {
            try {
                mqttMessageService.pushAlarm(alarm);
                alarm.setIsPushed(true);
                alarmRepository.save(alarm);
            } catch (Exception e) {
                log.error("Failed to push alarm: {}", alarm.getAlarmId(), e);
            }
        }
    }

    public void pushUnsentAlarms() {
        List<Alarm> unsentAlarms = alarmRepository.findByIsPushedFalse();
        log.info("Pushing {} unsent alarms via MQTT...", unsentAlarms.size());
        pushAlarms(unsentAlarms);
    }

    private void pushAllocationSuggestions(List<AllocationSuggestion> suggestions) {
        log.info("Pushing {} allocation suggestions via MQTT...", suggestions.size());
        for (AllocationSuggestion suggestion : suggestions) {
            try {
                mqttMessageService.pushAllocationSuggestion(suggestion);
            } catch (Exception e) {
                log.error("Failed to push allocation suggestion for well: {}", suggestion.getWellId(), e);
            }
        }
    }

    private void publishAlarmTriggeredEvent(String alarmLevel, List<Alarm> alarms) {
        AlarmTriggeredEvent event = new AlarmTriggeredEvent(this, alarmLevel, alarms);
        eventPublisher.publishEvent(event);
        log.debug("Published AlarmTriggeredEvent, level={}, count={}", alarmLevel, alarms.size());
    }

    public List<Alarm> getAllAlarms() {
        return alarmRepository.findAll();
    }

    public List<Alarm> getUnacknowledgedAlarms() {
        return alarmRepository.findByIsAcknowledgedFalseOrderByAlarmTimeDesc();
    }

    public List<Alarm> getAlarmsByWell(String wellId) {
        return alarmRepository.findByWellIdOrderByAlarmTimeDesc(wellId);
    }

    public List<Alarm> getAlarmsByLevel(String level) {
        return alarmRepository.findByAlarmLevelOrderByAlarmTimeDesc(level);
    }

    @Transactional
    public Alarm acknowledgeAlarm(Long id) {
        return alarmRepository.findById(id).map(alarm -> {
            alarm.setIsAcknowledged(true);
            alarm.setAcknowledgeTime(LocalDateTime.now());
            return alarmRepository.save(alarm);
        }).orElse(null);
    }

    public Long getUnacknowledgedCount() {
        return alarmRepository.countUnacknowledgedAlarms();
    }

    public void triggerManualAlarmCheck() {
        log.info("Manual alarm check triggered");
        List<Alarm> level1Alarms = checkWaterCutAlarms();
        List<Alarm> level2Alarms = checkPressureAlarms();
        log.info("Manual alarm check completed, new alarms: {}", level1Alarms.size() + level2Alarms.size());
    }
}
