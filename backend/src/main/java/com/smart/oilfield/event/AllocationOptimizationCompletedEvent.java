package com.smart.oilfield.event;

import com.smart.oilfield.entity.AllocationSuggestion;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class AllocationOptimizationCompletedEvent extends ApplicationEvent {

    private final String blockName;
    private final List<AllocationSuggestion> suggestions;
    private final LocalDateTime optimizationTime;
    private final double objectiveValue;
    private final int suggestionCount;

    public AllocationOptimizationCompletedEvent(Object source, String blockName,
                                                List<AllocationSuggestion> suggestions,
                                                double objectiveValue) {
        super(source);
        this.blockName = blockName;
        this.suggestions = suggestions;
        this.objectiveValue = objectiveValue;
        this.optimizationTime = LocalDateTime.now();
        this.suggestionCount = suggestions != null ? suggestions.size() : 0;
    }

    public boolean hasSuggestions() {
        return suggestions != null && !suggestions.isEmpty();
    }
}
