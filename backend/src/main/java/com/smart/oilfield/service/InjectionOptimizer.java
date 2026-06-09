package com.smart.oilfield.service;

import com.smart.oilfield.config.WaterfloodProperties;
import com.smart.oilfield.dto.AllocationSuggestionDTO;
import com.smart.oilfield.entity.AllocationSuggestion;
import com.smart.oilfield.entity.InjectionProductionRelation;
import com.smart.oilfield.entity.ProductionData;
import com.smart.oilfield.entity.Well;
import com.smart.oilfield.entity.WaterInjectionData;
import com.smart.oilfield.event.AllocationOptimizationCompletedEvent;
import com.smart.oilfield.event.WaterfloodAnalysisCompletedEvent;
import com.smart.oilfield.repository.AllocationSuggestionRepository;
import com.smart.oilfield.repository.WaterInjectionDataRepository;
import com.smart.oilfield.repository.WellRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.optim.MaxIter;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.linear.*;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class InjectionOptimizer {

    @Autowired
    private WellRepository wellRepository;

    @Autowired
    private WaterInjectionDataRepository injectionDataRepository;

    @Autowired
    private AllocationSuggestionRepository suggestionRepository;

    @Autowired
    private WaterfloodAnalyzer waterfloodAnalyzer;

    @Autowired
    private WaterfloodProperties waterfloodProperties;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Value("${allocation.days-interval:7}")
    private Integer daysInterval;

    @Value("${allocation.model-version:1.0.0}")
    private String modelVersion;

    @EventListener
    public void onWaterfloodAnalysisCompleted(WaterfloodAnalysisCompletedEvent event) {
        log.debug("Received WaterfloodAnalysisCompletedEvent for block: {}", event.getBlockName());
    }

    @Scheduled(cron = "${allocation.schedule:0 0 2 * * ?}")
    public void scheduledAllocationOptimization() {
        LocalDate today = LocalDate.now();
        LocalDate lastRun = today.minusDays(daysInterval);

        boolean hasRecentRun = !suggestionRepository.findFromDate(lastRun).isEmpty();
        if (hasRecentRun) {
            log.info("Skipping allocation optimization - recent run exists");
            return;
        }

        runFullOptimization(today);
    }

    public void runFullOptimization() {
        runFullOptimization(LocalDate.now());
    }

    @Transactional
    public void runFullOptimization(LocalDate date) {
        log.info("Starting full allocation optimization...");

        List<String> blocks = wellRepository.findAll().stream()
                .map(Well::getBlockName)
                .distinct()
                .collect(Collectors.toList());

        List<AllocationSuggestion> allSuggestions = new ArrayList<>();
        for (String block : blocks) {
            try {
                List<AllocationSuggestion> suggestions = optimizeBlockAllocation(block, date);
                allSuggestions.addAll(suggestions);
            } catch (Exception e) {
                log.error("Failed to optimize allocation for block: {}", block, e);
            }
        }

        log.info("Allocation optimization completed, total suggestions: {}", allSuggestions.size());
    }

    @Transactional
    public List<AllocationSuggestion> optimizeBlockAllocation(String blockName, LocalDate date) {
        log.info("Optimizing allocation for block: {}", blockName);

        List<Well> injectionWells = wellRepository
                .findByWellTypeAndBlockName("INJECTION", blockName)
                .stream()
                .filter(w -> "ACTIVE".equals(w.getStatus()))
                .collect(Collectors.toList());

        List<Well> productionWells = wellRepository
                .findByWellTypeAndBlockName("PRODUCTION", blockName)
                .stream()
                .filter(w -> "ACTIVE".equals(w.getStatus()))
                .collect(Collectors.toList());

        if (injectionWells.isEmpty() || productionWells.isEmpty()) {
            log.warn("No active wells in block: {}", blockName);
            return Collections.emptyList();
        }

        Map<String, Double> currentInjection = getCurrentInjectionVolumes(injectionWells);
        Map<String, double[]> waterFloodParams = waterfloodAnalyzer.calculateWaterFloodParameters(blockName);
        Map<String, List<InjectionProductionRelation>> relations = waterfloodAnalyzer.getInjectionRelations(injectionWells);

        double[] optimalVolumes = solveLinearProgram(
                injectionWells,
                productionWells,
                currentInjection,
                waterFloodParams,
                relations
        );

        List<AllocationSuggestion> suggestions = generateSuggestions(
                injectionWells, currentInjection, optimalVolumes, relations, waterFloodParams, date);

        if (!suggestions.isEmpty()) {
            suggestionRepository.saveAll(suggestions);

            double objectiveValue = calculateObjectiveValue(optimalVolumes, injectionWells, waterFloodParams, relations);
            AllocationOptimizationCompletedEvent event = new AllocationOptimizationCompletedEvent(
                    this, blockName, suggestions, objectiveValue);
            eventPublisher.publishEvent(event);
        }

        return suggestions;
    }

    private double[] solveLinearProgram(
            List<Well> injectionWells,
            List<Well> productionWells,
            Map<String, Double> currentInjection,
            Map<String, double[]> waterFloodParams,
            Map<String, List<InjectionProductionRelation>> relations) {

        int n = injectionWells.size();
        log.info("Solving linear program for {} injection wells", n);

        if (n <= waterfloodProperties.getAllocation().getSimplexThreshold()) {
            return solveWithSimplex(injectionWells, currentInjection, waterFloodParams, relations);
        } else {
            return solveWithDualDecomposition(injectionWells, currentInjection, waterFloodParams, relations);
        }
    }

    private double[] solveWithSimplex(
            List<Well> injectionWells,
            Map<String, Double> currentInjection,
            Map<String, double[]> waterFloodParams,
            Map<String, List<InjectionProductionRelation>> relations) {

        int n = injectionWells.size();
        double[] coefficients = buildCoefficients(injectionWells, waterFloodParams, relations);

        LinearObjectiveFunction objective = new LinearObjectiveFunction(coefficients, 0);
        List<LinearConstraint> constraints = buildConstraints(injectionWells, currentInjection);

        try {
            long start = System.currentTimeMillis();
            SimplexSolver solver = new SimplexSolver();
            PointValuePair solution = solver.optimize(
                    new MaxIter(500),
                    objective,
                    new LinearConstraintSet(constraints),
                    GoalType.MAXIMIZE,
                    new NonNegativeConstraint(true)
            );

            log.info("Simplex solved {} variables in {}ms, objective: {}",
                    n, System.currentTimeMillis() - start, solution.getValue());
            return solution.getPoint();

        } catch (Exception e) {
            log.error("Simplex failed, using current values", e);
            return getCurrentValues(injectionWells, currentInjection);
        }
    }

    private double[] solveWithDualDecomposition(
            List<Well> injectionWells,
            Map<String, Double> currentInjection,
            Map<String, double[]> waterFloodParams,
            Map<String, List<InjectionProductionRelation>> relations) {

        int n = injectionWells.size();
        WaterfloodProperties.Allocation allocProps = waterfloodProperties.getAllocation();
        long start = System.currentTimeMillis();

        Map<String, Integer> wellIndexMap = new HashMap<>();
        for (int i = 0; i < n; i++) {
            wellIndexMap.put(injectionWells.get(i).getWellId(), i);
        }

        Map<String, List<Integer>> subproblems = buildSubproblems(injectionWells, relations, wellIndexMap);
        log.info("Decomposed into {} subproblems", subproblems.size());

        double[] result = getCurrentValues(injectionWells, currentInjection);
        double[] dualVariables = new double[subproblems.size()];
        double stepSize = allocProps.getInitialStepSize();
        int maxIterations = allocProps.getMaxIterations();
        double convergenceThreshold = allocProps.getConvergenceThreshold();

        double totalTarget = currentInjection.values().stream().mapToDouble(Double::doubleValue).sum();
        double[] subproblemWeights = calculateSubproblemWeights(subproblems, injectionWells, currentInjection, totalTarget);

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            double[] newResult = new double[n];
            System.arraycopy(result, 0, newResult, 0, n);

            double primalResidual = 0;
            int subIdx = 0;
            for (Map.Entry<String, List<Integer>> entry : subproblems.entrySet()) {
                List<Integer> subproblemIndices = entry.getValue();
                double subTarget = totalTarget * subproblemWeights[subIdx] + dualVariables[subIdx];

                double[] subSolution = solveSubproblem(
                        injectionWells, subproblemIndices, currentInjection,
                        waterFloodParams, relations, subTarget);

                for (int i = 0; i < subproblemIndices.size(); i++) {
                    int wellIdx = subproblemIndices.get(i);
                    double diff = subSolution[i] - result[wellIdx];
                    primalResidual += Math.abs(diff);
                    newResult[wellIdx] = subSolution[i];
                }
                subIdx++;
            }

            double totalInjection = 0;
            for (double v : newResult) {
                totalInjection += v;
            }
            double balanceResidual = Math.abs(totalInjection - totalTarget) / totalTarget;

            updateDualVariables(dualVariables, subproblems, newResult, injectionWells,
                    currentInjection, totalTarget, subproblemWeights, stepSize);

            double dualResidual = 0;
            for (double d : dualVariables) {
                dualResidual += Math.abs(d);
            }

            if (primalResidual < convergenceThreshold * n &&
                balanceResidual < convergenceThreshold &&
                iteration > 5) {
                log.info("Dual decomposition converged at iteration {}, primal={}, balance={}",
                        iteration, primalResidual, balanceResidual);
                result = newResult;
                break;
            }

            result = newResult;
            stepSize = Math.max(stepSize * allocProps.getStepDecayRate(), allocProps.getMinStepSize());
        }

        result = adjustSolutionToBounds(result, injectionWells, currentInjection, totalTarget);

        log.info("Dual decomposition solved {} variables in {}ms",
                n, System.currentTimeMillis() - start);
        return result;
    }

    private Map<String, List<Integer>> buildSubproblems(
            List<Well> injectionWells,
            Map<String, List<InjectionProductionRelation>> relations,
            Map<String, Integer> wellIndexMap) {

        Map<String, List<Integer>> subproblems = new HashMap<>();
        Map<String, String> wellToCommunity = new HashMap<>();
        int nextCommunityId = 0;
        int maxSize = waterfloodProperties.getAllocation().getMaxSubproblemSize();

        for (Well injWell : injectionWells) {
            String wellId = injWell.getWellId();
            List<InjectionProductionRelation> wellRelations = relations.get(wellId);

            if (wellRelations == null || wellRelations.isEmpty()) {
                String community = "comm_" + (nextCommunityId++);
                wellToCommunity.put(wellId, community);
                subproblems.computeIfAbsent(community, k -> new ArrayList<>())
                        .add(wellIndexMap.get(wellId));
                continue;
            }

            Set<String> connectedCommunities = findConnectedCommunities(
                    wellId, wellRelations, injectionWells, relations, wellToCommunity);

            String targetCommunity = mergeCommunities(connectedCommunities, subproblems,
                    wellToCommunity, injectionWells, nextCommunityId);

            wellToCommunity.put(wellId, targetCommunity);
            subproblems.computeIfAbsent(targetCommunity, k -> new ArrayList<>())
                    .add(wellIndexMap.get(wellId));
        }

        return splitLargeSubproblems(subproblems, maxSize, nextCommunityId);
    }

    private Set<String> findConnectedCommunities(
            String wellId,
            List<InjectionProductionRelation> wellRelations,
            List<Well> injectionWells,
            Map<String, List<InjectionProductionRelation>> relations,
            Map<String, String> wellToCommunity) {

        Set<String> connectedCommunities = new HashSet<>();
        for (InjectionProductionRelation rel : wellRelations) {
            String prodWellId = rel.getProductionWellId();
            for (Well otherWell : injectionWells) {
                String otherWellId = otherWell.getWellId();
                if (otherWellId.equals(wellId)) continue;
                List<InjectionProductionRelation> otherRelations = relations.get(otherWellId);
                if (otherRelations != null) {
                    boolean connected = otherRelations.stream()
                            .anyMatch(r -> prodWellId.equals(r.getProductionWellId()));
                    if (connected && wellToCommunity.containsKey(otherWellId)) {
                        connectedCommunities.add(wellToCommunity.get(otherWellId));
                    }
                }
            }
        }
        return connectedCommunities;
    }

    private String mergeCommunities(
            Set<String> connectedCommunities,
            Map<String, List<Integer>> subproblems,
            Map<String, String> wellToCommunity,
            List<Well> injectionWells,
            int nextCommunityId) {

        String targetCommunity;
        if (connectedCommunities.isEmpty()) {
            targetCommunity = "comm_" + (nextCommunityId++);
        } else {
            targetCommunity = connectedCommunities.iterator().next();
            for (String otherCommunity : connectedCommunities) {
                if (!otherCommunity.equals(targetCommunity)) {
                    List<Integer> wellsToMove = subproblems.remove(otherCommunity);
                    if (wellsToMove != null) {
                        subproblems.get(targetCommunity).addAll(wellsToMove);
                        for (int wellIdx : wellsToMove) {
                            String movedWellId = injectionWells.get(wellIdx).getWellId();
                            wellToCommunity.put(movedWellId, targetCommunity);
                        }
                    }
                }
            }
        }
        return targetCommunity;
    }

    private Map<String, List<Integer>> splitLargeSubproblems(
            Map<String, List<Integer>> subproblems,
            int maxSize,
            int splitCounter) {

        Map<String, List<Integer>> finalSubproblems = new HashMap<>();
        for (Map.Entry<String, List<Integer>> entry : subproblems.entrySet()) {
            List<Integer> wells = entry.getValue();
            if (wells.size() <= maxSize) {
                finalSubproblems.put(entry.getKey(), wells);
            } else {
                for (int i = 0; i < wells.size(); i += maxSize) {
                    int end = Math.min(i + maxSize, wells.size());
                    String splitKey = entry.getKey() + "_split_" + (splitCounter++);
                    finalSubproblems.put(splitKey, new ArrayList<>(wells.subList(i, end)));
                }
            }
        }
        return finalSubproblems;
    }

    private double[] calculateSubproblemWeights(
            Map<String, List<Integer>> subproblems,
            List<Well> injectionWells,
            Map<String, Double> currentInjection,
            double totalTarget) {

        double[] weights = new double[subproblems.size()];
        int idx = 0;
        for (List<Integer> subproblem : subproblems.values()) {
            double subTotal = 0;
            for (int wellIdx : subproblem) {
                subTotal += currentInjection.getOrDefault(injectionWells.get(wellIdx).getWellId(), 0.0);
            }
            weights[idx] = subTotal / totalTarget;
            idx++;
        }
        return weights;
    }

    private double[] solveSubproblem(
            List<Well> allInjectionWells,
            List<Integer> subproblemIndices,
            Map<String, Double> currentInjection,
            Map<String, double[]> waterFloodParams,
            Map<String, List<InjectionProductionRelation>> relations,
            double subTarget) {

        int m = subproblemIndices.size();
        double[] coefficients = new double[m];
        WaterfloodProperties.Curve curveProps = waterfloodProperties.getCurve();

        for (int i = 0; i < m; i++) {
            Well injWell = allInjectionWells.get(subproblemIndices.get(i));
            List<InjectionProductionRelation> wellRelations = relations.get(injWell.getWellId());

            double oilGainCoefficient = 0;
            double waterCutPenalty = 0;

            if (wellRelations != null) {
                for (InjectionProductionRelation rel : wellRelations) {
                    double[] params = waterFloodParams.get(rel.getProductionWellId());
                    if (params != null) {
                        double effectiveness = rel.getEffectivenessDegree() != null ?
                                rel.getEffectivenessDegree() / 100.0 :
                                waterfloodProperties.getEffectiveness().getDefaultValue();
                        oilGainCoefficient += params[0] * effectiveness * curveProps.getOilGainMultiplier();
                        waterCutPenalty += params[2] * effectiveness * curveProps.getWaterCutPenaltyMultiplier();
                    }
                }
            }

            coefficients[i] = oilGainCoefficient - waterCutPenalty;
        }

        LinearObjectiveFunction objective = new LinearObjectiveFunction(coefficients, 0);
        List<LinearConstraint> constraints = buildSubproblemConstraints(
                subproblemIndices, allInjectionWells, currentInjection, subTarget);

        try {
            SimplexSolver solver = new SimplexSolver();
            PointValuePair solution = solver.optimize(
                    new MaxIter(300),
                    objective,
                    new LinearConstraintSet(constraints),
                    GoalType.MAXIMIZE,
                    new NonNegativeConstraint(true)
            );
            return solution.getPoint();
        } catch (Exception e) {
            double[] result = new double[m];
            for (int i = 0; i < m; i++) {
                result[i] = currentInjection.getOrDefault(
                        allInjectionWells.get(subproblemIndices.get(i)).getWellId(), 0.0);
            }
            return result;
        }
    }

    private void updateDualVariables(
            double[] dualVariables,
            Map<String, List<Integer>> subproblems,
            double[] result,
            List<Well> injectionWells,
            Map<String, Double> currentInjection,
            double totalTarget,
            double[] subproblemWeights,
            double stepSize) {

        int subIdx = 0;
        for (List<Integer> subproblem : subproblems.values()) {
            double subTotal = 0;
            for (int wellIdx : subproblem) {
                subTotal += result[wellIdx];
            }
            double subTarget = totalTarget * subproblemWeights[subIdx];
            dualVariables[subIdx] += stepSize * (subTotal - subTarget);
            subIdx++;
        }
    }

    private double[] adjustSolutionToBounds(
            double[] result,
            List<Well> injectionWells,
            Map<String, Double> currentInjection,
            double totalTarget) {

        WaterfloodProperties.Allocation allocProps = waterfloodProperties.getAllocation();
        double maxIncrease = allocProps.getMaxWaterIncreaseRate();
        double maxDecrease = allocProps.getMaxWaterDecreaseRate();
        double minVolume = allocProps.getMinWaterVolume();

        double totalInjection = 0;
        for (double v : result) {
            totalInjection += v;
        }
        double scalingFactor = totalTarget / totalInjection;

        for (int i = 0; i < injectionWells.size(); i++) {
            double current = currentInjection.getOrDefault(injectionWells.get(i).getWellId(), 0.0);
            double upperBound = current * (1 + maxIncrease);
            double lowerBound = Math.max(current * (1 - maxDecrease), minVolume);

            result[i] = result[i] * scalingFactor;
            result[i] = Math.min(result[i], upperBound);
            result[i] = Math.max(result[i], lowerBound);
        }

        return result;
    }

    private List<LinearConstraint> buildSubproblemConstraints(
            List<Integer> subproblemIndices,
            List<Well> allInjectionWells,
            Map<String, Double> currentInjection,
            double subTarget) {

        WaterfloodProperties.Allocation allocProps = waterfloodProperties.getAllocation();
        int m = subproblemIndices.size();
        List<LinearConstraint> constraints = new ArrayList<>(2 * m + 1);

        double[] equalityCoeff = new double[m];
        double subCurrentTotal = 0;
        for (int i = 0; i < m; i++) {
            equalityCoeff[i] = 1.0;
            subCurrentTotal += currentInjection.getOrDefault(
                    allInjectionWells.get(subproblemIndices.get(i)).getWellId(), 0.0);
        }

        double constraintTarget = subTarget > 0 ? subTarget : subCurrentTotal;
        constraints.add(new LinearConstraint(equalityCoeff, Relationship.EQ, constraintTarget));

        for (int i = 0; i < m; i++) {
            double current = currentInjection.getOrDefault(
                    allInjectionWells.get(subproblemIndices.get(i)).getWellId(), 0.0);
            double upperBound = current * (1 + allocProps.getMaxWaterIncreaseRate());
            double lowerBound = Math.max(current * (1 - allocProps.getMaxWaterDecreaseRate()),
                    allocProps.getMinWaterVolume());

            double[] boundCoeff = new double[m];
            boundCoeff[i] = 1.0;
            constraints.add(new LinearConstraint(boundCoeff, Relationship.LEQ, upperBound));
            constraints.add(new LinearConstraint(boundCoeff, Relationship.GEQ, lowerBound));
        }

        return constraints;
    }

    private double[] buildCoefficients(
            List<Well> injectionWells,
            Map<String, double[]> waterFloodParams,
            Map<String, List<InjectionProductionRelation>> relations) {

        WaterfloodProperties.Curve curveProps = waterfloodProperties.getCurve();
        WaterfloodProperties.Effectiveness effProps = waterfloodProperties.getEffectiveness();
        int n = injectionWells.size();
        double[] coefficients = new double[n];

        for (int i = 0; i < n; i++) {
            Well injWell = injectionWells.get(i);
            List<InjectionProductionRelation> wellRelations = relations.get(injWell.getWellId());

            double oilGainCoefficient = 0;
            double waterCutPenalty = 0;

            if (wellRelations != null) {
                for (InjectionProductionRelation rel : wellRelations) {
                    double[] params = waterFloodParams.get(rel.getProductionWellId());
                    if (params != null) {
                        double effectiveness = rel.getEffectivenessDegree() != null ?
                                rel.getEffectivenessDegree() / 100.0 : effProps.getDefaultValue();
                        oilGainCoefficient += params[0] * effectiveness * curveProps.getOilGainMultiplier();
                        waterCutPenalty += params[2] * effectiveness * curveProps.getWaterCutPenaltyMultiplier();
                    }
                }
            }

            coefficients[i] = oilGainCoefficient - waterCutPenalty;
        }

        return coefficients;
    }

    private List<LinearConstraint> buildConstraints(
            List<Well> injectionWells,
            Map<String, Double> currentInjection) {

        WaterfloodProperties.Allocation allocProps = waterfloodProperties.getAllocation();
        int n = injectionWells.size();
        List<LinearConstraint> constraints = new ArrayList<>(2 * n + 1);

        double[] equalityCoeff = new double[n];
        double totalCurrentInjection = 0;
        for (int i = 0; i < n; i++) {
            equalityCoeff[i] = 1.0;
            totalCurrentInjection += currentInjection.getOrDefault(injectionWells.get(i).getWellId(), 0.0);
        }
        constraints.add(new LinearConstraint(equalityCoeff, Relationship.EQ, totalCurrentInjection));

        for (int i = 0; i < n; i++) {
            double current = currentInjection.getOrDefault(injectionWells.get(i).getWellId(), 0.0);
            double maxIncrease = current * (1 + allocProps.getMaxWaterIncreaseRate());
            double maxDecrease = Math.max(current * (1 - allocProps.getMaxWaterDecreaseRate()),
                    allocProps.getMinWaterVolume());

            double[] upperBound = new double[n];
            upperBound[i] = 1.0;
            constraints.add(new LinearConstraint(upperBound, Relationship.LEQ, maxIncrease));

            double[] lowerBound = new double[n];
            lowerBound[i] = 1.0;
            constraints.add(new LinearConstraint(lowerBound, Relationship.GEQ, maxDecrease));
        }

        return constraints;
    }

    private Map<String, Double> getCurrentInjectionVolumes(List<Well> injectionWells) {
        Map<String, Double> currentInjection = new HashMap<>();
        for (Well well : injectionWells) {
            WaterInjectionData latest = injectionDataRepository.findLatestByWellId(well.getWellId());
            if (latest != null) {
                currentInjection.put(well.getWellId(), latest.getWaterVolume());
            } else {
                currentInjection.put(well.getWellId(), 100.0);
            }
        }
        return currentInjection;
    }

    private double[] getCurrentValues(List<Well> injectionWells, Map<String, Double> currentInjection) {
        int n = injectionWells.size();
        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            result[i] = currentInjection.getOrDefault(injectionWells.get(i).getWellId(), 0.0);
        }
        return result;
    }

    private List<AllocationSuggestion> generateSuggestions(
            List<Well> injectionWells,
            Map<String, Double> currentInjection,
            double[] optimalVolumes,
            Map<String, List<InjectionProductionRelation>> relations,
            Map<String, double[]> waterFloodParams,
            LocalDate date) {

        WaterfloodProperties.Allocation allocProps = waterfloodProperties.getAllocation();
        List<AllocationSuggestion> suggestions = new ArrayList<>();

        for (int i = 0; i < injectionWells.size(); i++) {
            Well well = injectionWells.get(i);
            double current = currentInjection.getOrDefault(well.getWellId(), 0.0);
            double suggested = optimalVolumes[i];
            double adjustment = suggested - current;

            AllocationSuggestion suggestion = new AllocationSuggestion();
            suggestion.setWellId(well.getWellId());
            suggestion.setSuggestionDate(date);
            suggestion.setCurrentWaterVolume(current);
            suggestion.setSuggestedWaterVolume(Math.round(suggested * 100.0) / 100.0);
            suggestion.setAdjustmentAmount(Math.round(adjustment * 100.0) / 100.0);

            if (Math.abs(adjustment) / current < allocProps.getMinAdjustmentRate()) {
                suggestion.setAdjustmentDirection("KEEP");
            } else if (adjustment > 0) {
                suggestion.setAdjustmentDirection("INCREASE");
            } else {
                suggestion.setAdjustmentDirection("DECREASE");
            }

            suggestion.setModelVersion(modelVersion);
            suggestion.setReason(generateReason(well, current, suggested, adjustment,
                    relations.get(well.getWellId()), waterFloodParams));

            suggestions.add(suggestion);
        }

        return suggestions;
    }

    private String generateReason(
            Well well,
            double current,
            double suggested,
            double adjustment,
            List<InjectionProductionRelation> relations,
            Map<String, double[]> waterFloodParams) {

        double avgEffectiveness = 0;
        if (relations != null && !relations.isEmpty()) {
            avgEffectiveness = relations.stream()
                    .mapToDouble(r -> r.getEffectivenessDegree() != null ?
                            r.getEffectivenessDegree() : waterfloodProperties.getEffectiveness().getDefaultValue() * 100)
                    .average()
                    .orElse(waterfloodProperties.getEffectiveness().getDefaultValue() * 100);
        }

        String directionText = adjustment > 0 ? "增加" : adjustment < 0 ? "减少" : "保持";

        return String.format(
                "基于水驱特征曲线分析，该井注采受效程度%.1f%%，建议%s注水量%.2f m³",
                avgEffectiveness, directionText, Math.abs(adjustment));
    }

    private double calculateObjectiveValue(
            double[] optimalVolumes,
            List<Well> injectionWells,
            Map<String, double[]> waterFloodParams,
            Map<String, List<InjectionProductionRelation>> relations) {

        double[] coefficients = buildCoefficients(injectionWells, waterFloodParams, relations);
        double objective = 0;
        for (int i = 0; i < optimalVolumes.length; i++) {
            objective += coefficients[i] * optimalVolumes[i];
        }
        return objective;
    }

    public List<AllocationSuggestion> getLatestSuggestions() {
        return suggestionRepository.findAll();
    }

    public List<AllocationSuggestion> getSuggestionsByWell(String wellId) {
        return suggestionRepository.findByWellIdOrderBySuggestionDateDesc(wellId);
    }
}
