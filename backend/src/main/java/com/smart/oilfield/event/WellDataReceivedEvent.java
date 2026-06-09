package com.smart.oilfield.event;

import com.smart.oilfield.entity.ProductionData;
import com.smart.oilfield.entity.WaterInjectionData;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.List;

@Getter
public class WellDataReceivedEvent extends ApplicationEvent {

    private final String wellType;
    private final List<WaterInjectionData> injectionDataList;
    private final List<ProductionData> productionDataList;
    private final LocalDateTime receivedTime;
    private final int recordCount;

    public WellDataReceivedEvent(Object source, String wellType,
                                  List<WaterInjectionData> injectionDataList,
                                  List<ProductionData> productionDataList) {
        super(source);
        this.wellType = wellType;
        this.injectionDataList = injectionDataList;
        this.productionDataList = productionDataList;
        this.receivedTime = LocalDateTime.now();
        this.recordCount = (injectionDataList != null ? injectionDataList.size() : 0) +
                          (productionDataList != null ? productionDataList.size() : 0);
    }

    public boolean hasInjectionData() {
        return injectionDataList != null && !injectionDataList.isEmpty();
    }

    public boolean hasProductionData() {
        return productionDataList != null && !productionDataList.isEmpty();
    }
}
