package com.smart.oilfield.event;

import com.smart.oilfield.entity.Alarm;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class AlarmTriggeredEvent extends ApplicationEvent {

    private final String alarmLevel;
    private final List<Alarm> alarms;
    private final LocalDateTime triggerTime;
    private final int alarmCount;

    public AlarmTriggeredEvent(Object source, String alarmLevel, List<Alarm> alarms) {
        super(source);
        this.alarmLevel = alarmLevel;
        this.alarms = alarms;
        this.triggerTime = LocalDateTime.now();
        this.alarmCount = alarms != null ? alarms.size() : 0;
    }

    public boolean hasAlarms() {
        return alarms != null && !alarms.isEmpty();
    }

    public boolean isLevel1() {
        return "LEVEL_1".equals(alarmLevel);
    }

    public boolean isLevel2() {
        return "LEVEL_2".equals(alarmLevel);
    }
}
