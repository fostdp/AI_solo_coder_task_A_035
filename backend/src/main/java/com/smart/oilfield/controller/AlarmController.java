package com.smart.oilfield.controller;

import com.smart.oilfield.entity.Alarm;
import com.smart.oilfield.service.AlarmPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/alarms")
public class AlarmController {

    @Autowired
    private AlarmPublisher alarmPublisher;

    @GetMapping
    public ResponseEntity<List<Alarm>> getAllAlarms(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String wellId) {

        List<Alarm> alarms;
        if (wellId != null) {
            alarms = alarmPublisher.getAlarmsByWell(wellId);
        } else if (level != null) {
            alarms = alarmPublisher.getAlarmsByLevel(level);
        } else {
            alarms = alarmPublisher.getAllAlarms();
        }
        return ResponseEntity.ok(alarms);
    }

    @GetMapping("/unacknowledged")
    public ResponseEntity<Map<String, Object>> getUnacknowledgedAlarms() {
        List<Alarm> alarms = alarmPublisher.getUnacknowledgedAlarms();
        Long count = alarmPublisher.getUnacknowledgedCount();

        Map<String, Object> result = new HashMap<>();
        result.put("count", count);
        result.put("alarms", alarms);
        result.put("level1Count", alarms.stream()
                .filter(a -> "LEVEL_1".equals(a.getAlarmLevel())).count());
        result.put("level2Count", alarms.stream()
                .filter(a -> "LEVEL_2".equals(a.getAlarmLevel())).count());

        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<Alarm> acknowledgeAlarm(@PathVariable Long id) {
        Alarm alarm = alarmPublisher.acknowledgeAlarm(id);
        if (alarm == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(alarm);
    }

    @PostMapping("/check-now")
    public ResponseEntity<Map<String, Object>> triggerAlarmCheck() {
        alarmPublisher.triggerManualAlarmCheck();
        alarmPublisher.pushUnsentAlarms();

        Map<String, Object> result = new HashMap<>();
        result.put("message", "Alarm check completed");
        result.put("unacknowledgedCount", alarmPublisher.getUnacknowledgedCount());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/push-unsent")
    public ResponseEntity<Map<String, Object>> pushUnsentAlarms() {
        alarmPublisher.pushUnsentAlarms();
        Map<String, Object> result = new HashMap<>();
        result.put("message", "Unsent alarms pushed successfully");
        return ResponseEntity.ok(result);
    }
}
