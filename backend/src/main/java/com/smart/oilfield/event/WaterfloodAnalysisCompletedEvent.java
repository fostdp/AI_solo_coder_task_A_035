package com.smart.oilfield.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
public class WaterfloodAnalysisCompletedEvent extends ApplicationEvent {

    private final String blockName;
    private final Map<String, double[]> waterFloodParameters;
    private final Map<String, Double> effectivenessDegrees;
    private final LocalDateTime analysisTime;
    private final int analyzedWellCount;

    public WaterfloodAnalysisCompletedEvent(Object source, String blockName,
                                            Map<String, double[]> waterFloodParameters,
                                            Map<String, Double> effectivenessDegrees) {
        super(source);
        this.blockName = blockName;
        this.waterFloodParameters = waterFloodParameters;
        this.effectivenessDegrees = effectivenessDegrees;
        this.analysisTime = LocalDateTime.now();
        this.analyzedWellCount = waterFloodParameters != null ? waterFloodParameters.size() : 0;
    }
}
