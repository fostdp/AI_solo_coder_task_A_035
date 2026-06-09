const WellMap = {
    map: null,
    canvas: null,
    ctx: null,
    wells: [],
    relations: [],
    suggestions: [],
    selectedWell: null,
    layerVisibility: {
        injection: true,
        production: true,
        relations: true,
        allocation: true
    },
    currentBlock: 'ALL',
    onWellSelectCallback: null,

    init(onWellSelect) {
        this.onWellSelectCallback = onWellSelect;
        this.map = L.map('map', {
            center: CONFIG.MAP_CENTER,
            zoom: CONFIG.MAP_ZOOM,
            zoomControl: true,
            attributionControl: true
        });

        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            attribution: '© OpenStreetMap contributors',
            maxZoom: 18
        }).addTo(this.map);

        this.canvas = document.getElementById('well-canvas');
        this.ctx = this.canvas.getContext('2d');

        this.resizeCanvas();
        window.addEventListener('resize', () => this.resizeCanvas());

        this.map.on('moveend', () => this.render());
        this.map.on('zoomend', () => this.render());
        this.map.on('resize', () => this.resizeCanvas());

        this.setupCanvasInteraction();

        this.render();
    },

    resizeCanvas() {
        const container = document.querySelector('.map-container');
        this.canvas.width = container.clientWidth;
        this.canvas.height = container.clientHeight;
        this.render();
    },

    setupCanvasInteraction() {
        const mapContainer = document.getElementById('map');
        
        mapContainer.addEventListener('click', (e) => {
            const rect = this.canvas.getBoundingClientRect();
            const x = e.clientX - rect.left;
            const y = e.clientY - rect.top;
            
            const clickedWell = this.findWellAtPosition(x, y);
            if (clickedWell) {
                this.selectWell(clickedWell);
            }
        });

        mapContainer.addEventListener('mousemove', (e) => {
            const rect = this.canvas.getBoundingClientRect();
            const x = e.clientX - rect.left;
            const y = e.clientY - rect.top;
            
            const hoveredWell = this.findWellAtPosition(x, y);
            this.showTooltip(hoveredWell, e.clientX, e.clientY);
            
            mapContainer.style.cursor = hoveredWell ? 'pointer' : 'grab';
        });

        mapContainer.addEventListener('mouseleave', () => {
            this.hideTooltip();
        });
    },

    findWellAtPosition(x, y) {
        for (let i = this.wells.length - 1; i >= 0; i--) {
            const well = this.wells[i];
            const point = this.map.latLngToContainerPoint([well.latitude, well.longitude]);
            
            const dx = x - point.x;
            const dy = y - point.y;
            const distance = Math.sqrt(dx * dx + dy * dy);
            
            if (distance <= CONFIG.WELL_RADIUS + 4) {
                return well;
            }
        }
        return null;
    },

    selectWell(well) {
        this.selectedWell = well;
        if (this.onWellSelectCallback) {
            this.onWellSelectCallback(well);
        }
        this.render();
    },

    clearSelection() {
        this.selectedWell = null;
        this.render();
    },

    showTooltip(well, clientX, clientY) {
        let tooltip = document.getElementById('well-tooltip');
        
        if (!well) {
            this.hideTooltip();
            return;
        }

        if (!tooltip) {
            tooltip = document.createElement('div');
            tooltip.id = 'well-tooltip';
            tooltip.className = 'tooltip';
            document.body.appendChild(tooltip);
        }

        let content = `<div class="tooltip-title">${well.wellName}</div>`;
        content += `<div class="tooltip-row"><span class="tooltip-label">类型:</span><span class="tooltip-value">${well.wellType === 'INJECTION' ? '注水井' : '采油井'}</span></div>`;
        content += `<div class="tooltip-row"><span class="tooltip-label">区块:</span><span class="tooltip-value">${well.blockName}</span></div>`;

        if (well.wellType === 'INJECTION') {
            content += `<div class="tooltip-row"><span class="tooltip-label">日注水量:</span><span class="tooltip-value">${well.latestWaterVolume?.toFixed(2) || '--'} m³</span></div>`;
            content += `<div class="tooltip-row"><span class="tooltip-label">注水压力:</span><span class="tooltip-value">${well.latestInjectionPressure?.toFixed(2) || '--'} MPa</span></div>`;
        } else {
            content += `<div class="tooltip-row"><span class="tooltip-label">日产油量:</span><span class="tooltip-value">${well.latestOilVolume?.toFixed(2) || '--'} t</span></div>`;
            content += `<div class="tooltip-row"><span class="tooltip-label">含水率:</span><span class="tooltip-value">${well.latestWaterCut?.toFixed(2) || '--'}%</span></div>`;
        }

        tooltip.innerHTML = content;
        tooltip.style.left = (clientX + 15) + 'px';
        tooltip.style.top = (clientY + 15) + 'px';
        tooltip.style.display = 'block';
    },

    hideTooltip() {
        const tooltip = document.getElementById('well-tooltip');
        if (tooltip) {
            tooltip.style.display = 'none';
        }
    },

    async loadData() {
        try {
            const [wells, relations, suggestions] = await Promise.all([
                API.getWells(null, this.currentBlock),
                API.getRelations(this.currentBlock),
                API.getLatestSuggestions()
            ]);

            this.wells = wells;
            this.relations = relations.lines || [];
            this.suggestions = suggestions.suggestions || [];

            this.render();
        } catch (error) {
            console.error('Failed to load map data:', error);
        }
    },

    setBlock(blockName) {
        this.currentBlock = blockName;
        this.loadData();
    },

    setLayerVisibility(layer, visible) {
        this.layerVisibility[layer] = visible;
        this.render();
    },

    render() {
        if (!this.ctx || !this.map) return;

        this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);

        if (this.layerVisibility.relations) {
            this.renderRelations();
        }

        if (this.layerVisibility.allocation) {
            this.renderAllocationArrows();
        }

        if (this.layerVisibility.injection) {
            this.renderWells('INJECTION');
        }

        if (this.layerVisibility.production) {
            this.renderWells('PRODUCTION');
        }
    },

    renderRelations() {
        this.ctx.lineWidth = 2;
        this.ctx.globalAlpha = 0.6;

        for (const relation of this.relations) {
            const coords = relation.coordinates;
            if (!coords || coords.length < 2) continue;

            const start = this.map.latLngToContainerPoint([coords[0][1], coords[0][0]]);
            const end = this.map.latLngToContainerPoint([coords[1][1], coords[1][0]]);

            this.ctx.beginPath();
            this.ctx.moveTo(start.x, start.y);
            this.ctx.lineTo(end.x, end.y);
            this.ctx.strokeStyle = relation.color || '#888';
            this.ctx.stroke();

            this.drawArrowHead(start, end, relation.color);
        }

        this.ctx.globalAlpha = 1;
    },

    drawArrowHead(start, end, color) {
        const headLength = 8;
        const angle = Math.atan2(end.y - start.y, end.x - start.x);

        this.ctx.beginPath();
        this.ctx.moveTo(end.x, end.y);
        this.ctx.lineTo(
            end.x - headLength * Math.cos(angle - Math.PI / 6),
            end.y - headLength * Math.sin(angle - Math.PI / 6)
        );
        this.ctx.moveTo(end.x, end.y);
        this.ctx.lineTo(
            end.x - headLength * Math.cos(angle + Math.PI / 6),
            end.y - headLength * Math.sin(angle + Math.PI / 6)
        );
        this.ctx.strokeStyle = color;
        this.ctx.stroke();
    },

    renderWells(type) {
        const filteredWells = this.wells.filter(w => w.wellType === type);

        for (const well of filteredWells) {
            const point = this.map.latLngToContainerPoint([well.latitude, well.longitude]);
            
            const isSelected = this.selectedWell && this.selectedWell.wellId === well.wellId;
            const radius = isSelected ? CONFIG.WELL_RADIUS + 4 : CONFIG.WELL_RADIUS;

            if (type === 'INJECTION') {
                this.drawInjectionWell(point.x, point.y, radius, isSelected, well);
            } else {
                this.drawProductionWell(point.x, point.y, radius, isSelected, well);
            }
        }
    },

    drawInjectionWell(x, y, radius, isSelected, well) {
        const gradient = this.ctx.createRadialGradient(x, y, 0, x, y, radius);
        gradient.addColorStop(0, CONFIG.COLORS.INJECTION);
        gradient.addColorStop(1, '#1565c0');

        this.ctx.beginPath();
        this.ctx.arc(x, y, radius, 0, Math.PI * 2);
        this.ctx.fillStyle = gradient;
        this.ctx.fill();

        this.ctx.strokeStyle = isSelected ? '#ffeb3b' : CONFIG.COLORS.INJECTION_STROKE;
        this.ctx.lineWidth = isSelected ? 3 : 2;
        this.ctx.stroke();

        this.ctx.fillStyle = '#fff';
        this.ctx.font = 'bold 10px Arial';
        this.ctx.textAlign = 'center';
        this.ctx.textBaseline = 'middle';
        this.ctx.fillText('注', x, y);
    },

    drawProductionWell(x, y, radius, isSelected, well) {
        this.ctx.beginPath();
        this.ctx.moveTo(x, y - radius);
        this.ctx.lineTo(x - radius, y + radius * 0.8);
        this.ctx.lineTo(x + radius, y + radius * 0.8);
        this.ctx.closePath();

        const gradient = this.ctx.createLinearGradient(x, y - radius, x, y + radius);
        gradient.addColorStop(0, CONFIG.COLORS.PRODUCTION);
        gradient.addColorStop(1, '#c62828');

        this.ctx.fillStyle = gradient;
        this.ctx.fill();

        this.ctx.strokeStyle = isSelected ? '#ffeb3b' : CONFIG.COLORS.PRODUCTION_STROKE;
        this.ctx.lineWidth = isSelected ? 3 : 2;
        this.ctx.stroke();

        this.ctx.fillStyle = '#fff';
        this.ctx.font = 'bold 9px Arial';
        this.ctx.textAlign = 'center';
        this.ctx.textBaseline = 'middle';
        this.ctx.fillText('采', x, y + 2);
    },

    renderAllocationArrows() {
        for (const suggestion of this.suggestions) {
            if (suggestion.adjustmentDirection === 'KEEP') continue;

            const well = this.wells.find(w => w.wellId === suggestion.wellId);
            if (!well) continue;

            const point = this.map.latLngToContainerPoint([well.latitude, well.longitude]);
            
            const isIncrease = suggestion.adjustmentDirection === 'INCREASE';
            const arrowLength = 20 + Math.min(Math.abs(suggestion.adjustmentAmount) / 2, 20);
            const direction = isIncrease ? -1 : 1;

            this.ctx.beginPath();
            this.ctx.moveTo(point.x, point.y - CONFIG.WELL_RADIUS - 5);
            this.ctx.lineTo(point.x, point.y - CONFIG.WELL_RADIUS - 5 - arrowLength * direction);
            this.ctx.strokeStyle = isIncrease ? CONFIG.COLORS.ALLOCATION_INCREASE : CONFIG.COLORS.ALLOCATION_DECREASE;
            this.ctx.lineWidth = 3;
            this.ctx.stroke();

            const arrowHeadSize = 6;
            const arrowY = point.y - CONFIG.WELL_RADIUS - 5 - arrowLength * direction;
            
            this.ctx.beginPath();
            this.ctx.moveTo(point.x, arrowY);
            this.ctx.lineTo(point.x - arrowHeadSize, arrowY + arrowHeadSize * direction);
            this.ctx.lineTo(point.x + arrowHeadSize, arrowY + arrowHeadSize * direction);
            this.ctx.closePath();
            this.ctx.fillStyle = isIncrease ? CONFIG.COLORS.ALLOCATION_INCREASE : CONFIG.COLORS.ALLOCATION_DECREASE;
            this.ctx.fill();

            this.ctx.fillStyle = isIncrease ? CONFIG.COLORS.ALLOCATION_INCREASE : CONFIG.COLORS.ALLOCATION_DECREASE;
            this.ctx.font = 'bold 11px Arial';
            this.ctx.textAlign = 'center';
            const labelY = arrowY - (isIncrease ? 8 : -8);
            this.ctx.fillText((isIncrease ? '+' : '') + suggestion.adjustmentAmount.toFixed(1), point.x, labelY);
        }
    },

    flyToWell(lat, lng, zoom) {
        this.map.flyTo([lat, lng], zoom || 14);
    }
};
