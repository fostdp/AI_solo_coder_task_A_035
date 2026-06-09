package com.smart.oilfield.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "waterflood")
public class WaterfloodProperties {

    private History history = new History();
    private Allocation allocation = new Allocation();
    private Curve curve = new Curve();
    private Effectiveness effectiveness = new Effectiveness();

    @Data
    public static class History {
        private int days = 30;
        private int minRegressionDays = 7;
    }

    @Data
    public static class Allocation {
        private double maxWaterIncreaseRate = 0.2;
        private double maxWaterDecreaseRate = 0.3;
        private double minAdjustmentRate = 0.02;
        private double minWaterVolume = 10.0;
        private int simplexThreshold = 50;
        private int maxSubproblemSize = 40;
        private int maxIterations = 100;
        private double convergenceThreshold = 1e-6;
        private double initialStepSize = 0.01;
        private double stepDecayRate = 0.99;
        private double minStepSize = 1e-4;
    }

    @Data
    public static class Curve {
        private double defaultSlope = 0.015;
        private double defaultIntercept = 0.0;
        private double defaultWaterCutRate = 0.001;
        private double oilGainMultiplier = 1000.0;
        private double waterCutPenaltyMultiplier = 500.0;
    }

    @Data
    public static class Effectiveness {
        private double highThreshold = 0.7;
        private double mediumThreshold = 0.4;
        private double defaultValue = 0.5;
    }
}
