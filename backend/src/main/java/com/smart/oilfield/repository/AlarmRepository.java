package com.smart.oilfield.repository;

import com.smart.oilfield.entity.Alarm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlarmRepository extends JpaRepository<Alarm, Long> {

    List<Alarm> findByWellIdOrderByAlarmTimeDesc(String wellId);

    List<Alarm> findByAlarmLevelOrderByAlarmTimeDesc(String alarmLevel);

    List<Alarm> findByIsPushedFalse();

    List<Alarm> findByIsAcknowledgedFalseOrderByAlarmTimeDesc();

    @Query("SELECT a FROM Alarm a WHERE a.alarmTime >= :startTime " +
           "ORDER BY a.alarmTime DESC")
    List<Alarm> findFromTime(@Param("startTime") LocalDateTime startTime);

    @Query("SELECT a FROM Alarm a WHERE a.alarmLevel = :level " +
           "AND a.alarmTime >= :startTime ORDER BY a.alarmTime DESC")
    List<Alarm> findByLevelFromTime(@Param("level") String level,
                                    @Param("startTime") LocalDateTime startTime);

    @Query("SELECT COUNT(a) FROM Alarm a WHERE a.isAcknowledged = false")
    Long countUnacknowledgedAlarms();

    boolean existsByWellIdAndAlarmTypeAndAlarmTimeAfter(String wellId, String alarmType, LocalDateTime alarmTime);
}
