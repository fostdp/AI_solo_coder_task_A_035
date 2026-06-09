package com.smart.oilfield.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.oilfield.dto.InjectionDataDTO;
import com.smart.oilfield.dto.ProductionDataDTO;
import com.smart.oilfield.entity.ProductionData;
import com.smart.oilfield.entity.WaterInjectionData;
import com.smart.oilfield.event.WellDataReceivedEvent;
import com.smart.oilfield.repository.ProductionDataRepository;
import com.smart.oilfield.repository.WaterInjectionDataRepository;
import com.smart.oilfield.repository.WellRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class WellDataReceiver {

    @Autowired
    private WaterInjectionDataRepository injectionDataRepository;

    @Autowired
    private ProductionDataRepository productionDataRepository;

    @Autowired
    private WellRepository wellRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private static final int BATCH_SIZE = 100;
    private static final int BATCH_FLUSH_INTERVAL_MS = 5000;
    private static final int QUEUE_CAPACITY = 10000;

    private final BlockingQueue<WaterInjectionData> injectionDataQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final BlockingQueue<ProductionData> productionDataQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    private final AtomicInteger injectionQueueSize = new AtomicInteger(0);
    private final AtomicInteger productionQueueSize = new AtomicInteger(0);

    private ExecutorService batchExecutor;
    private volatile boolean running = true;

    @PostConstruct
    public void init() {
        batchExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "data-batch-writer");
            t.setDaemon(true);
            return t;
        });

        batchExecutor.submit(this::processInjectionQueue);
        batchExecutor.submit(this::processProductionQueue);

        log.info("WellDataReceiver initialized, batchSize={}, flushInterval={}ms",
                BATCH_SIZE, BATCH_FLUSH_INTERVAL_MS);
    }

    @PreDestroy
    public void destroy() {
        running = false;
        if (batchExecutor != null) {
            batchExecutor.shutdownNow();
        }
        flushAllQueues();
        log.info("WellDataReceiver shutdown, remaining injection={}, production={}",
                injectionQueueSize.get(), productionQueueSize.get());
    }

    private void processInjectionQueue() {
        List<WaterInjectionData> batch = new ArrayList<>(BATCH_SIZE);
        long lastFlushTime = System.currentTimeMillis();

        while (running || !injectionDataQueue.isEmpty()) {
            try {
                WaterInjectionData data = injectionDataQueue.poll(100, TimeUnit.MILLISECONDS);
                if (data != null) {
                    batch.add(data);
                    injectionQueueSize.decrementAndGet();
                }

                long now = System.currentTimeMillis();
                if (batch.size() >= BATCH_SIZE || (now - lastFlushTime >= BATCH_FLUSH_INTERVAL_MS && !batch.isEmpty())) {
                    saveInjectionBatch(batch);
                    batch.clear();
                    lastFlushTime = now;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing injection queue", e);
                if (!batch.isEmpty()) {
                    saveInjectionBatch(batch);
                    batch.clear();
                }
            }
        }

        if (!batch.isEmpty()) {
            saveInjectionBatch(batch);
        }
    }

    private void processProductionQueue() {
        List<ProductionData> batch = new ArrayList<>(BATCH_SIZE);
        long lastFlushTime = System.currentTimeMillis();

        while (running || !productionDataQueue.isEmpty()) {
            try {
                ProductionData data = productionDataQueue.poll(100, TimeUnit.MILLISECONDS);
                if (data != null) {
                    batch.add(data);
                    productionQueueSize.decrementAndGet();
                }

                long now = System.currentTimeMillis();
                if (batch.size() >= BATCH_SIZE || (now - lastFlushTime >= BATCH_FLUSH_INTERVAL_MS && !batch.isEmpty())) {
                    saveProductionBatch(batch);
                    batch.clear();
                    lastFlushTime = now;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing production queue", e);
                if (!batch.isEmpty()) {
                    saveProductionBatch(batch);
                    batch.clear();
                }
            }
        }

        if (!batch.isEmpty()) {
            saveProductionBatch(batch);
        }
    }

    private void saveInjectionBatch(List<WaterInjectionData> batch) {
        if (batch.isEmpty()) return;
        try {
            long start = System.currentTimeMillis();
            List<WaterInjectionData> saved = injectionDataRepository.saveAll(batch);
            log.info("Batch saved {} injection records in {}ms", batch.size(), System.currentTimeMillis() - start);
            publishDataReceivedEvent("INJECTION", saved, null);
        } catch (Exception e) {
            log.error("Failed to save injection batch of size {}", batch.size(), e);
        }
    }

    private void saveProductionBatch(List<ProductionData> batch) {
        if (batch.isEmpty()) return;
        try {
            long start = System.currentTimeMillis();
            List<ProductionData> saved = productionDataRepository.saveAll(batch);
            log.info("Batch saved {} production records in {}ms", batch.size(), System.currentTimeMillis() - start);
            publishDataReceivedEvent("PRODUCTION", null, saved);
        } catch (Exception e) {
            log.error("Failed to save production batch of size {}", batch.size(), e);
        }
    }

    private void publishDataReceivedEvent(String wellType,
                                           List<WaterInjectionData> injectionData,
                                           List<ProductionData> productionData) {
        WellDataReceivedEvent event = new WellDataReceivedEvent(
                this, wellType, injectionData, productionData);
        eventPublisher.publishEvent(event);
        log.debug("Published WellDataReceivedEvent, type={}, count={}", wellType, event.getRecordCount());
    }

    @Scheduled(fixedDelay = 10000)
    public void flushAllQueues() {
        if (injectionQueueSize.get() > 0 || productionQueueSize.get() > 0) {
            log.debug("Scheduled flush triggered, injection queue: {}, production queue: {}",
                    injectionQueueSize.get(), productionQueueSize.get());
        }
    }

    public void receiveInjectionData(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String wellId = node.get("wellId").asText();

            if (!validateWellExists(wellId, "INJECTION")) {
                log.warn("Received data for unknown injection well: {}", wellId);
                return;
            }

            WaterInjectionData data = parseInjectionData(node, wellId);
            if (!validateInjectionData(data)) {
                log.warn("Invalid injection data for well: {}", wellId);
                return;
            }

            enqueueData(injectionDataQueue, data, injectionQueueSize, wellId);
        } catch (Exception e) {
            log.error("Failed to parse injection data: {}", payload, e);
        }
    }

    public void receiveProductionData(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String wellId = node.get("wellId").asText();

            if (!validateWellExists(wellId, "PRODUCTION")) {
                log.warn("Received data for unknown production well: {}", wellId);
                return;
            }

            ProductionData data = parseProductionData(node, wellId);
            if (!validateProductionData(data)) {
                log.warn("Invalid production data for well: {}", wellId);
                return;
            }

            enqueueData(productionDataQueue, data, productionQueueSize, wellId);
        } catch (Exception e) {
            log.error("Failed to parse production data: {}", payload, e);
        }
    }

    private boolean validateWellExists(String wellId, String expectedType) {
        if (!wellRepository.existsById(wellId)) {
            return false;
        }
        return wellRepository.findById(wellId)
                .map(w -> expectedType.equals(w.getWellType()))
                .orElse(false);
    }

    private boolean validateInjectionData(WaterInjectionData data) {
        if (data.getWaterVolume() == null || data.getWaterVolume() < 0) return false;
        if (data.getInjectionPressure() == null || data.getInjectionPressure() < 0) return false;
        if (data.getWaterAbsorptionIndex() == null || data.getWaterAbsorptionIndex() < 0) return false;
        return data.getReportDate() != null;
    }

    private boolean validateProductionData(ProductionData data) {
        if (data.getLiquidVolume() == null || data.getLiquidVolume() < 0) return false;
        if (data.getOilVolume() == null || data.getOilVolume() < 0) return false;
        if (data.getWaterCut() == null || data.getWaterCut() < 0 || data.getWaterCut() > 100) return false;
        if (data.getDynamicFluidLevel() == null || data.getDynamicFluidLevel() < 0) return false;
        if (data.getOilVolume() > data.getLiquidVolume()) return false;
        return data.getReportDate() != null;
    }

    private WaterInjectionData parseInjectionData(JsonNode node, String wellId) {
        WaterInjectionData data = new WaterInjectionData();
        data.setWellId(wellId);
        data.setReportDate(node.has("reportDate") ?
                LocalDate.parse(node.get("reportDate").asText()) : LocalDate.now());
        data.setWaterVolume(node.get("waterVolume").asDouble());
        data.setInjectionPressure(node.get("injectionPressure").asDouble());
        data.setWaterAbsorptionIndex(node.get("waterAbsorptionIndex").asDouble());
        return data;
    }

    private ProductionData parseProductionData(JsonNode node, String wellId) {
        ProductionData data = new ProductionData();
        data.setWellId(wellId);
        data.setReportDate(node.has("reportDate") ?
                LocalDate.parse(node.get("reportDate").asText()) : LocalDate.now());
        data.setLiquidVolume(node.get("liquidVolume").asDouble());
        data.setOilVolume(node.get("oilVolume").asDouble());
        data.setWaterCut(node.get("waterCut").asDouble());
        data.setDynamicFluidLevel(node.get("dynamicFluidLevel").asDouble());
        return data;
    }

    private <T> void enqueueData(BlockingQueue<T> queue, T data, AtomicInteger counter, String wellId) {
        try {
            boolean offered = queue.offer(data, 1, TimeUnit.SECONDS);
            if (offered) {
                counter.incrementAndGet();
            } else {
                log.warn("Queue full, dropping data for well: {}", wellId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while enqueuing data for well: {}", wellId);
        }
    }

    public WaterInjectionData saveInjectionData(InjectionDataDTO dto) {
        if (!validateWellExists(dto.getWellId(), "INJECTION")) {
            throw new RuntimeException("Well not found: " + dto.getWellId());
        }

        WaterInjectionData data = new WaterInjectionData();
        data.setWellId(dto.getWellId());
        data.setReportDate(dto.getReportDate() != null ? dto.getReportDate() : LocalDate.now());
        data.setWaterVolume(dto.getWaterVolume());
        data.setInjectionPressure(dto.getInjectionPressure());
        data.setWaterAbsorptionIndex(dto.getWaterAbsorptionIndex());

        if (!validateInjectionData(data)) {
            throw new RuntimeException("Invalid injection data");
        }

        try {
            boolean offered = injectionDataQueue.offer(data, 1, TimeUnit.SECONDS);
            if (offered) {
                injectionQueueSize.incrementAndGet();
                return data;
            } else {
                log.warn("Queue full, falling back to direct save for well: {}", dto.getWellId());
                WaterInjectionData saved = injectionDataRepository.save(data);
                publishDataReceivedEvent("INJECTION", List.of(saved), null);
                return saved;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            WaterInjectionData saved = injectionDataRepository.save(data);
            publishDataReceivedEvent("INJECTION", List.of(saved), null);
            return saved;
        }
    }

    public ProductionData saveProductionData(ProductionDataDTO dto) {
        if (!validateWellExists(dto.getWellId(), "PRODUCTION")) {
            throw new RuntimeException("Well not found: " + dto.getWellId());
        }

        ProductionData data = new ProductionData();
        data.setWellId(dto.getWellId());
        data.setReportDate(dto.getReportDate() != null ? dto.getReportDate() : LocalDate.now());
        data.setLiquidVolume(dto.getLiquidVolume());
        data.setOilVolume(dto.getOilVolume());
        data.setWaterCut(dto.getWaterCut());
        data.setDynamicFluidLevel(dto.getDynamicFluidLevel());

        if (!validateProductionData(data)) {
            throw new RuntimeException("Invalid production data");
        }

        try {
            boolean offered = productionDataQueue.offer(data, 1, TimeUnit.SECONDS);
            if (offered) {
                productionQueueSize.incrementAndGet();
                return data;
            } else {
                log.warn("Queue full, falling back to direct save for well: {}", dto.getWellId());
                ProductionData saved = productionDataRepository.save(data);
                publishDataReceivedEvent("PRODUCTION", null, List.of(saved));
                return saved;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ProductionData saved = productionDataRepository.save(data);
            publishDataReceivedEvent("PRODUCTION", null, List.of(saved));
            return saved;
        }
    }

    public int getInjectionQueueSize() {
        return injectionQueueSize.get();
    }

    public int getProductionQueueSize() {
        return productionQueueSize.get();
    }
}
