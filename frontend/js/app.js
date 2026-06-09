let currentBlock = 'ALL';
let refreshTimer = null;

document.addEventListener('DOMContentLoaded', () => {
    init();
});

async function init() {
    WellMap.init(showWellDetail);
    setupEventListeners();
    await loadBlocks();
    await refreshAllData();
    startAutoRefresh();
    updateCurrentTime();
    setInterval(updateCurrentTime, 1000);
}

function setupEventListeners() {
    document.getElementById('block-selector').addEventListener('change', (e) => {
        currentBlock = e.target.value;
        WellMap.setBlock(currentBlock);
        refreshCoreIndicators();
    });

    document.getElementById('show-injection').addEventListener('change', (e) => {
        WellMap.setLayerVisibility('injection', e.target.checked);
    });

    document.getElementById('show-production').addEventListener('change', (e) => {
        WellMap.setLayerVisibility('production', e.target.checked);
    });

    document.getElementById('show-relations').addEventListener('change', (e) => {
        WellMap.setLayerVisibility('relations', e.target.checked);
    });

    document.getElementById('show-allocation').addEventListener('change', (e) => {
        WellMap.setLayerVisibility('allocation', e.target.checked);
    });
}

async function loadBlocks() {
    try {
        const data = await API.getBlocks();
        const selector = document.getElementById('block-selector');
        
        for (const block of data.blocks || []) {
            const option = document.createElement('option');
            option.value = block;
            option.textContent = block;
            selector.appendChild(option);
        }
    } catch (error) {
        console.error('Failed to load blocks:', error);
    }
}

async function refreshAllData() {
    await Promise.all([
        WellMap.loadData(),
        refreshCoreIndicators(),
        refreshAlarms()
    ]);
}

async function refreshCoreIndicators() {
    try {
        const data = await API.getCoreIndicators(currentBlock);
        
        document.getElementById('daily-oil').textContent = 
            data.dailyOilProduction?.toFixed(2) || '--';
        document.getElementById('daily-water').textContent = 
            data.dailyWaterInjection?.toFixed(2) || '--';
        document.getElementById('water-cut').textContent = 
            data.comprehensiveWaterCut?.toFixed(2) || '--';

        const changes = data.dayOverDayChanges || {};
        
        const oilChangeEl = document.getElementById('oil-change');
        const waterChangeEl = document.getElementById('water-change');
        const cutChangeEl = document.getElementById('cut-change');

        updateChangeIndicator(oilChangeEl, changes.oilChange, true);
        updateChangeIndicator(waterChangeEl, changes.waterChange, false);
        updateChangeIndicator(cutChangeEl, changes.waterCutChange, false);

    } catch (error) {
        console.error('Failed to refresh core indicators:', error);
    }
}

function updateChangeIndicator(element, value, isOil) {
    if (value === undefined || value === null) {
        element.textContent = '--';
        element.className = 'indicator-change';
        return;
    }

    const prefix = value >= 0 ? '+' : '';
    element.textContent = `${prefix}${value.toFixed(2)}%`;
    
    if (isOil) {
        element.className = value >= 0 ? 'indicator-change positive' : 'indicator-change negative';
    } else {
        element.className = value <= 0 ? 'indicator-change positive' : 'indicator-change negative';
    }
}

async function refreshAlarms() {
    try {
        const data = await API.getAlarms();
        
        document.getElementById('alarm-badge').textContent = data.count || 0;
        
        const alarmListEl = document.getElementById('alarm-list');
        
        if (!data.alarms || data.alarms.length === 0) {
            alarmListEl.innerHTML = '<div class="no-data">暂无告警</div>';
            return;
        }

        alarmListEl.innerHTML = data.alarms.slice(0, 10).map(alarm => `
            <div class="alarm-item level-${alarm.alarmLevel === 'LEVEL_1' ? '1' : '2'}" 
                 onclick="handleAlarmClick(${alarm.id}, '${alarm.wellId}')">
                <div class="alarm-type">${alarm.alarmLevel === 'LEVEL_1' ? '一级水淹预警' : '二级井筒异常'}</div>
                <div class="alarm-message">${alarm.alarmMessage}</div>
                <div class="alarm-time">${formatTime(alarm.alarmTime)}</div>
            </div>
        `).join('');

    } catch (error) {
        console.error('Failed to refresh alarms:', error);
    }
}

function handleAlarmClick(alarmId, wellId) {
    API.acknowledgeAlarm(alarmId).then(() => {
        refreshAlarms();
    });
    
    if (wellId) {
        API.getWellById(wellId).then(well => {
            if (well) {
                WellMap.selectWell(well);
                WellMap.flyToWell(well.latitude, well.longitude, 14);
            }
        });
    }
}

async function runAllocation() {
    if (confirm('确定要立即生成注水调配建议吗？此过程可能需要几秒钟。')) {
        try {
            await API.runAllocation();
            await WellMap.loadData();
            alert('调配建议生成成功！');
        } catch (error) {
            console.error('Failed to run allocation:', error);
            alert('调配建议生成失败，请检查后端服务。');
        }
    }
}

async function checkAlarms() {
    try {
        await API.checkAlarms();
        await refreshAlarms();
        alert('告警检查完成！');
    } catch (error) {
        console.error('Failed to check alarms:', error);
        alert('告警检查失败，请检查后端服务。');
    }
}

function startAutoRefresh() {
    if (refreshTimer) {
        clearInterval(refreshTimer);
    }
    refreshTimer = setInterval(() => {
        refreshAllData();
    }, CONFIG.REFRESH_INTERVAL);
}

function updateCurrentTime() {
    const now = new Date();
    const timeStr = now.toLocaleString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
    });
    document.getElementById('current-time').textContent = timeStr;
}

function formatTime(timeStr) {
    if (!timeStr) return '--';
    const date = new Date(timeStr);
    return date.toLocaleString('zh-CN', {
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
    });
}
