package com.smart.oilfield.service;

import com.smart.oilfield.config.WaterfloodProperties;
import com.smart.oilfield.entity.InjectionProductionRelation;
import com.smart.oilfield.entity.ProductionData;
import com.smart.oilfield.entity.Well;
import com.smart.oilfield.event.WaterfloodAnalysisCompletedEvent;
import com.smart.oilfield.event.WellDataReceivedEvent;
import com.smart.oilfield.repository.InjectionProductionRelationRepository;
import com.smart.oilfield.repository.ProductionDataRepository;
import com.smart.oilfield.repository.WellRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WaterfloodAnalyzer {

    @Autowired
    private WellRepository wellRepository;

    @Autowired
    private ProductionDataRepository productionDataRepository;

    @Autowired
    private InjectionProductionRelationRepository relationRepository;

    @Autowired
    private WaterfloodProperties waterfloodProperties;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @EventListener
    @Async
    public void onDataReceived(WellDataReceivedEvent event) {
        log.debug("Received WellDataReceivedEvent, type={}, count={}",
                event.getWellType(), event.getRecordCount());

        if (event.hasProductionData()) {
            Set<String> affectedBlocks = event.getProductionDataList().stream()
                    .map(data -> {
                        return wellRepository.findById(data.getWellId())
                                .map(Well::getBlockName)
                                .orElse(null);
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            for (String block : affectedBlocks) {
                try {
                    analyzeBlockWaterflood(block);
                } catch (Exception e) {
                    log.error("Failed to analyze waterflood for block: {}", block, e);
                }
            }
        }
    }

    public Map<String, double[]> analyzeBlockWaterflood(String blockName) {
        log.info("Analyzing waterflood characteristics for block: {}", blockName);

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(waterfloodProperties.getHistory().getDays());

        Map<String, double[]> waterFloodParams = calculateWaterFloodParameters(blockName, startDate, endDate);
        Map<String, Double> effectivenessDegrees = calculateEffectivenessDegrees(blockName);

        WaterfloodAnalysisCompletedEvent event = new WaterfloodAnalysisCompletedEvent(
                this, blockName, waterFloodParams, effectivenessDegrees);
        eventPublisher.publishEvent(event);
        log.info("Waterflood analysis completed for block: {}, wells analyzed: {}", blockName, waterFloodParams.size());

        return waterFloodParams;
    }

    public Map<String, double[]> calculateWaterFloodParameters(String blockName) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(waterfloodProperties.getHistory().getDays());
        return calculateWaterFloodParameters(blockName, startDate, endDate);
    }

    public Map<String, double[]> calculateWaterFloodParameters(String blockName, LocalDate startDate, LocalDate endDate) {
        Map<String, double[]> params = new HashMap<>();

        List<Well> productionWells = wellRepository.findByWellTypeAndBlockName("PRODUCTION", blockName);
        int minDays = waterfloodProperties.getHistory().getMinRegressionDays();

        for (Well well : productionWells) {
            List<ProductionData> data = productionDataRepository
                    .findByWellIdAndReportDateBetweenOrderByReportDate(well.getWellId(), startDate, endDate);

            if (data.size() < minDays) {
                params.put(well.getWellId(), new double[]{
                        waterfloodProperties.getCurve().getDefaultSlope(),
                        waterfloodProperties.getCurve().getDefaultIntercept(),
                        waterfloodProperties.getCurve().getDefaultWaterCutRate()
                });
                continue;
            }

            double[] regression = performWaterFloodRegression(data);
            params.put(well.getWellId(), regression);
        }

        return params;
    }

    public double[] performWaterFloodRegression(List<ProductionData> data) {
        int n = data.size();
        double[] x = new double[n];
        double[] y = new double[n];

        double cumulativeOil = 0;
        double cumulativeWater = 0;

        for (int i = 0; i < n; i++) {
            ProductionData d = data.get(i);
            cumulativeOil += d.getOilVolume();
            cumulativeWater += (d.getLiquidVolume() - d.getOilVolume());
            x[i] = cumulativeOil;
            y[i] = Math.log10(Math.max(cumulativeWater, 1.0));
        }

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
        }

        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        double intercept = (sumY - slope * sumX) / n;
        double waterCutRate = calculateWaterCutRiseRate(data);

        return new double[]{slope, intercept, waterCutRate};
    }

    public double calculateWaterCutRiseRate(List<ProductionData> data) {
        if (data.size() < 2) return waterfloodProperties.getCurve().getDefaultWaterCutRate();

        double firstWaterCut = data.get(0).getWaterCut();
        double lastWaterCut = data.get(data.size() - 1).getWaterCut();
        int days = data.size();

        return Math.max((lastWaterCut - firstWaterCut) / days / 100.0,
                waterfloodProperties.getCurve().getDefaultWaterCutRate());
    }

    public Map<String, Double> calculateEffectivenessDegrees(String blockName) {
        Map<String, Double> effectivenessMap = new HashMap<>();

        List<InjectionProductionRelation> relations = relationRepository.findByBlockName(blockName);
        for (InjectionProductionRelation relation : relations) {
            if (relation.getEffectivenessDegree() != null) {
                String key = relation.getInjectionWellId() + "_" + relation.getProductionWellId();
                effectivenessMap.put(key, relation.getEffectivenessDegree() / 100.0);
            } else {
                String key = relation.getInjectionWellId() + "_" + relation.getProductionWellId();
                effectivenessMap.put(key, waterfloodProperties.getEffectiveness().getDefaultValue());
            }
        }

        return effectivenessMap;
    }

    public Map<String, List<InjectionProductionRelation>> getInjectionRelations(List<Well> injectionWells) {
        Map<String, List<InjectionProductionRelation>> relations = new HashMap<>();
        for (Well well : injectionWells) {
            relations.put(well.getWellId(),
                    relationRepository.findByInjectionWellId(well.getWellId()));
        }
        return relations;
    }

    public String getEffectivenessType(double effectiveness) {
        if (effectiveness >= waterfloodProperties.getEffectiveness().getHighThreshold()) {
            return "HIGH";
        } else if (effectiveness >= waterfloodProperties.getEffectiveness().getMediumThreshold()) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }

    public double calculateWellEffectiveness(String injectionWellId, String productionWellId) {
        return relationRepository.findByInjectionWellId(injectionWellId).stream()
                .filter(r -> productionWellId.equals(r.getProductionWellId()))
                .findFirst()
                .map(r -> r.getEffectivenessDegree() != null ?
                        r.getEffectivenessDegree() / 100.0 :
                        waterfloodProperties.getEffectiveness().getDefaultValue())
                .orElse(waterfloodProperties.getEffectiveness().getDefaultValue());
    }

    public double calculateOilGainCoefficient(double[] waterFloodParams, double effectiveness) {
        return waterFloodParams[0] * effectiveness * waterfloodProperties.getCurve().getOilGainMultiplier();
    }

    public double calculateWaterCutPenalty(double[] waterFloodParams, double effectiveness) {
        return waterFloodParams[2] * effectiveness * waterfloodProperties.getCurve().getWaterCutPenaltyMultiplier();
    }

    public List<LocalDate[]> getWaterfloodHistoryRanges() {
        List<LocalDate[]> ranges = new ArrayList<>();
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(waterfloodProperties.getHistory().getDays());
        ranges.add(new LocalDate[]{startDate, endDate});
        return ranges;
    }

    public void triggerFullAnalysis() {
        List<String> blocks = wellRepository.findAll().stream()
                .map(Well::getBlockName)
                .distinct()
                .collect(Collectors.toList());

        log.info("Triggering full waterflood analysis for {} blocks", blocks.size());
        for (String block : blocks) {
            try {
                analyzeBlockWaterflood(block);
            } catch (Exception e) {
                log.error("Failed to analyze block: {}", block, e);
            }
        }
    }
}
