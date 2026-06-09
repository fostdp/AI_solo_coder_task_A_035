package com.smart.oilfield.controller;

import com.smart.oilfield.dto.InjectionDataDTO;
import com.smart.oilfield.dto.ProductionDataDTO;
import com.smart.oilfield.entity.ProductionData;
import com.smart.oilfield.entity.WaterInjectionData;
import com.smart.oilfield.repository.ProductionDataRepository;
import com.smart.oilfield.repository.WaterInjectionDataRepository;
import com.smart.oilfield.service.WellDataReceiver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/data")
public class DataController {

    @Autowired
    private WellDataReceiver wellDataReceiver;

    @Autowired
    private WaterInjectionDataRepository injectionDataRepository;

    @Autowired
    private ProductionDataRepository productionDataRepository;

    @PostMapping("/injection")
    public ResponseEntity<WaterInjectionData> receiveInjectionData(
            @RequestBody InjectionDataDTO dto) {
        WaterInjectionData saved = wellDataReceiver.saveInjectionData(dto);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/production")
    public ResponseEntity<ProductionData> receiveProductionData(
            @RequestBody ProductionDataDTO dto) {
        ProductionData saved = wellDataReceiver.saveProductionData(dto);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/injection/batch")
    public ResponseEntity<Map<String, Object>> receiveInjectionBatch(
            @RequestBody List<InjectionDataDTO> dtos) {
        int success = 0;
        int failed = 0;
        for (InjectionDataDTO dto : dtos) {
            try {
                wellDataReceiver.saveInjectionData(dto);
                success++;
            } catch (Exception e) {
                failed++;
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("failed", failed);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/production/batch")
    public ResponseEntity<Map<String, Object>> receiveProductionBatch(
            @RequestBody List<ProductionDataDTO> dtos) {
        int success = 0;
        int failed = 0;
        for (ProductionDataDTO dto : dtos) {
            try {
                wellDataReceiver.saveProductionData(dto);
                success++;
            } catch (Exception e) {
                failed++;
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", success);
        result.put("failed", failed);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/injection/{wellId}")
    public ResponseEntity<List<WaterInjectionData>> getInjectionData(
            @PathVariable String wellId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<WaterInjectionData> data;
        if (startDate != null && endDate != null) {
            data = injectionDataRepository.findByWellIdAndReportDateBetweenOrderByReportDate(
                    wellId, startDate, endDate);
        } else {
            data = injectionDataRepository.findByWellIdOrderByReportDateDesc(wellId);
        }
        return ResponseEntity.ok(data);
    }

    @GetMapping("/production/{wellId}")
    public ResponseEntity<List<ProductionData>> getProductionData(
            @PathVariable String wellId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        List<ProductionData> data;
        if (startDate != null && endDate != null) {
            data = productionDataRepository.findByWellIdAndReportDateBetweenOrderByReportDate(
                    wellId, startDate, endDate);
        } else {
            data = productionDataRepository.findByWellIdOrderByReportDateDesc(wellId);
        }
        return ResponseEntity.ok(data);
    }
}
