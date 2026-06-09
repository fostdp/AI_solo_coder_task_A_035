const WellDetail = {
    currentWell: null,

    async show(well, onCloseCallback) {
        this.currentWell = well;
        const panel = document.getElementById('detail-panel');
        const titleEl = document.getElementById('detail-title');
        const contentEl = document.getElementById('detail-content');

        titleEl.textContent = `${well.wellName} - ${well.wellType === 'INJECTION' ? '注水井' : '采油井'}`;
        panel.style.display = 'flex';

        contentEl.innerHTML = `
            <div class="well-info-grid">
                <div class="info-item">
                    <div class="info-label">井号</div>
                    <div class="info-value">${well.wellId}</div>
                </div>
                <div class="info-item">
                    <div class="info-label">区块</div>
                    <div class="info-value">${well.blockName}</div>
                </div>
                <div class="info-item">
                    <div class="info-label">状态</div>
                    <div class="info-value">${well.status === 'ACTIVE' ? '正常' : '停用'}</div>
                </div>
                <div class="info-item">
                    <div class="info-label">经度</div>
                    <div class="info-value">${well.longitude?.toFixed(6) || '--'}</div>
                </div>
                <div class="info-item">
                    <div class="info-label">纬度</div>
                    <div class="info-value">${well.latitude?.toFixed(6) || '--'}</div>
                </div>
                ${well.wellType === 'INJECTION' ? `
                    <div class="info-item">
                        <div class="info-label">设计压力</div>
                        <div class="info-value">${well.designPressure?.toFixed(2) || '--'} MPa</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">日注水量</div>
                        <div class="info-value">${well.latestWaterVolume?.toFixed(2) || '--'} m³</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">注水压力</div>
                        <div class="info-value">${well.latestInjectionPressure?.toFixed(2) || '--'} MPa</div>
                    </div>
                ` : `
                    <div class="info-item">
                        <div class="info-label">日产油量</div>
                        <div class="info-value">${well.latestOilVolume?.toFixed(2) || '--'} t</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">含水率</div>
                        <div class="info-value">${well.latestWaterCut?.toFixed(2) || '--'}%</div>
                    </div>
                `}
            </div>

            <div class="chart-container">
                <h4>近90天生产趋势</h4>
                <div class="chart-wrapper">
                    <canvas id="trend-chart"></canvas>
                </div>
            </div>

            <div class="chart-container">
                <h4>注采对应分析</h4>
                <div class="chart-wrapper">
                    <canvas id="relation-chart"></canvas>
                </div>
            </div>

            <div id="allocation-section" style="${well.wellType === 'INJECTION' ? '' : 'display: none;'}">
                <div class="chart-container">
                    <h4>最新调配建议</h4>
                    <div id="allocation-content">
                        <div class="no-data">加载中...</div>
                    </div>
                </div>
            </div>

            <div class="relation-analysis">
                <h4>注采对应关系</h4>
                <div id="relation-list">
                    <div class="no-data">加载中...</div>
                </div>
            </div>
        `;

        try {
            const [trendData, relations, suggestions] = await Promise.all([
                API.getWellTrend(well.wellId, 90),
                API.getRelationsByWell(well.wellId, well.wellType),
                well.wellType === 'INJECTION' ? API.getLatestSuggestions() : null
            ]);

            if (trendData) {
                ChartManager.createProductionTrend('trend-chart', trendData);
            }

            if (well.wellType === 'INJECTION') {
                const wellSuggestion = suggestions?.suggestions?.find(s => s.wellId === well.wellId);
                this.renderAllocationSuggestion(wellSuggestion);
            }

            this.renderRelationAnalysis(relations, well.wellType);

            const relatedWellTrend = await this.getRelatedWellTrend(well, relations);
            if (relatedWellTrend) {
                ChartManager.createInjectionProductionAnalysis('relation-chart', trendData, relatedWellTrend);
            }

        } catch (error) {
            console.error('Failed to load well detail:', error);
        }

        this.onCloseCallback = onCloseCallback;
    },

    close() {
        document.getElementById('detail-panel').style.display = 'none';
        this.currentWell = null;
        ChartManager.destroyAll();
        if (this.onCloseCallback) {
            this.onCloseCallback();
        }
    },

    async getRelatedWellTrend(well, relations) {
        if (!relations || relations.length === 0) return null;

        let relatedWellId = null;
        if (well.wellType === 'INJECTION') {
            relatedWellId = relations[0]?.productionWellId;
        } else {
            relatedWellId = relations[0]?.injectionWellId;
        }

        if (!relatedWellId) return null;

        return await API.getWellTrend(relatedWellId, 90);
    },

    renderAllocationSuggestion(suggestion) {
        const contentEl = document.getElementById('allocation-content');
        
        if (!suggestion) {
            contentEl.innerHTML = '<div class="no-data">暂无调配建议</div>';
            return;
        }

        contentEl.innerHTML = `
            <div class="allocation-item">
                <div class="allocation-header">
                    <span>建议日期: ${suggestion.suggestionDate}</span>
                    <span class="direction ${suggestion.adjustmentDirection}">
                        ${suggestion.adjustmentDirection === 'INCREASE' ? '增加' : 
                          suggestion.adjustmentDirection === 'DECREASE' ? '减少' : '保持'}
                    </span>
                </div>
                <div style="display: flex; gap: 20px; margin-bottom: 8px;">
                    <div>
                        <div class="info-label">当前注水量</div>
                        <div class="info-value">${suggestion.currentWaterVolume.toFixed(2)} m³</div>
                    </div>
                    <div>
                        <div class="info-label">建议注水量</div>
                        <div class="info-value">${suggestion.suggestedWaterVolume.toFixed(2)} m³</div>
                    </div>
                    <div>
                        <div class="info-label">调整量</div>
                        <div class="info-value" style="color: ${
                            suggestion.adjustmentAmount > 0 ? '#4caf50' : 
                            suggestion.adjustmentAmount < 0 ? '#f44336' : '#9e9e9e'
                        }">
                            ${suggestion.adjustmentAmount > 0 ? '+' : ''}${suggestion.adjustmentAmount.toFixed(2)} m³
                        </div>
                    </div>
                </div>
                <div class="reason">${suggestion.reason || '暂无详细说明'}</div>
            </div>
        `;
    },

    renderRelationAnalysis(relations, wellType) {
        const listEl = document.getElementById('relation-list');
        
        if (!relations || relations.length === 0) {
            listEl.innerHTML = '<div class="no-data">暂无注采对应关系</div>';
            return;
        }

        const getEffectivenessClass = (type) => {
            switch (type) {
                case 'HIGH': return 'high';
                case 'MEDIUM': return 'medium';
                case 'LOW': return 'low';
                default: return '';
            }
        };

        const getEffectivenessText = (type) => {
            switch (type) {
                case 'HIGH': return '高效';
                case 'MEDIUM': return '中等';
                case 'LOW': return '无效';
                default: return '未知';
            }
        };

        listEl.innerHTML = relations.slice(0, 8).map(rel => {
            const relatedWellId = wellType === 'INJECTION' ? rel.productionWellId : rel.injectionWellId;
            return `
                <div class="relation-item ${getEffectivenessClass(rel.effectivenessType)}">
                    <span class="well-name">${relatedWellId}</span>
                    <span class="effectiveness">
                        ${getEffectivenessText(rel.effectivenessType)}
                        ${rel.effectivenessDegree ? `(${rel.effectivenessDegree.toFixed(1)}%)` : ''}
                    </span>
                </div>
            `;
        }).join('');
    },

    getCurrentWell() {
        return this.currentWell;
    }
};

function closeDetailPanel() {
    WellDetail.close();
}

function showWellDetail(well) {
    WellDetail.show(well, () => {
        if (window.WellMap) {
            WellMap.clearSelection();
        }
    });
}
