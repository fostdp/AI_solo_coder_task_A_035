package com.smart.oilfield.controller;

import com.smart.oilfield.entity.AllocationSuggestion;
import com.smart.oilfield.service.InjectionOptimizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/allocation")
public class AllocationController {

    @Autowired
    private InjectionOptimizer injectionOptimizer;

    @GetMapping("/latest")
    public ResponseEntity<Map<String, Object>> getLatestSuggestions() {
        List<AllocationSuggestion> suggestions = injectionOptimizer.getLatestSuggestions();

        Map<String, Object> result = new HashMap<>();
        result.put("suggestions", suggestions);
        result.put("totalCount", suggestions.size());
        result.put("increaseCount", suggestions.stream()
                .filter(s -> "INCREASE".equals(s.getAdjustmentDirection())).count());
        result.put("decreaseCount", suggestions.stream()
                .filter(s -> "DECREASE".equals(s.getAdjustmentDirection())).count());
        result.put("keepCount", suggestions.stream()
                .filter(s -> "KEEP".equals(s.getAdjustmentDirection())).count());

        if (!suggestions.isEmpty()) {
            result.put("suggestionDate", suggestions.get(0).getSuggestionDate());
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/well/{wellId}")
    public ResponseEntity<List<AllocationSuggestion>> getSuggestionsByWell(
            @PathVariable String wellId) {
        List<AllocationSuggestion> suggestions = injectionOptimizer.getSuggestionsByWell(wellId);
        return ResponseEntity.ok(suggestions);
    }

    @PostMapping("/run-now")
    public ResponseEntity<Map<String, Object>> runOptimizationNow() {
        injectionOptimizer.runFullOptimization();
        List<AllocationSuggestion> suggestions = injectionOptimizer.getLatestSuggestions();

        Map<String, Object> result = new HashMap<>();
        result.put("message", "Allocation optimization completed");
        result.put("suggestionsGenerated", suggestions.size());
        result.put("suggestions", suggestions);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/block/{blockName}")
    public ResponseEntity<Map<String, Object>> optimizeBlock(
            @PathVariable String blockName) {
        LocalDate today = LocalDate.now();
        var suggestions = injectionOptimizer.optimizeBlockAllocation(blockName, today);

        Map<String, Object> result = new HashMap<>();
        result.put("message", "Block optimization completed");
        result.put("blockName", blockName);
        result.put("suggestionsCount", suggestions.size());
        result.put("suggestionDate", today);
        return ResponseEntity.ok(result);
    }
}
