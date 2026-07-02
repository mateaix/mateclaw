<template>
  <div class="raw-panel">
    <!-- Upload + Add text row -->
    <div v-if="canManageWiki" class="upload-row">
      <div
        class="upload-zone"
        :class="{ 'is-dragging': isDragging, 'is-uploading': uploadingFiles.length > 0 }"
        @click="triggerFileInput"
        @dragover.prevent
        @dragenter.prevent="onDragEnter"
        @dragleave.prevent="onDragLeave"
        @drop.prevent="handleDrop"
      >
        <!-- Spinner while uploading -->
        <svg v-if="uploadingFiles.length > 0" class="upload-spinner" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M21 12a9 9 0 1 1-6.219-8.56"/>
        </svg>
        <!-- Arrow-up icon in drag-over state -->
        <svg v-else-if="isDragging" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <polyline points="17 8 12 3 7 8"/>
          <line x1="12" y1="3" x2="12" y2="21"/>
        </svg>
        <!-- Default upload icon -->
        <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
          <polyline points="17 8 12 3 7 8"/>
          <line x1="12" y1="3" x2="12" y2="15"/>
        </svg>
        <div class="upload-text">
          <span class="upload-label">
            <template v-if="uploadingFiles.length > 0">{{ t('wiki.uploading') }}</template>
            <template v-else-if="isDragging">{{ t('wiki.dropToUpload') }}</template>
            <template v-else>{{ t('wiki.dropFiles') }}</template>
          </span>
          <span class="upload-hint">.txt .md .csv .pdf .docx .xlsx .pptx .html</span>
        </div>
      </div>
      <input ref="fileInput" type="file" style="display:none" accept=".txt,.md,.csv,.pdf,.docx,.doc,.xlsx,.xls,.pptx,.ppt,.html,.htm" multiple @change="handleFileSelect" />
      <button class="btn-secondary add-text-btn" @click="showAddText = true">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
        {{ t('wiki.addText') }}
      </button>
    </div>

    <!-- Auto-sync (per-KB source watcher): periodically scans the configured directory -->
    <div v-if="canManageWiki" class="auto-sync-row">
      <label class="auto-sync-toggle" :class="{ disabled: !watcher.globalEnabled || watcher.busy }">
        <input
          type="checkbox"
          :checked="watcher.kbEnabled"
          :disabled="!watcher.globalEnabled || watcher.busy"
          @change="toggleWatcher(($event.target as HTMLInputElement).checked)"
        />
        <span>{{ t('wiki.sources.autoSync') }}</span>
      </label>
      <span v-if="watcher.globalEnabled && watcher.kbEnabled" class="auto-sync-meta">
        {{ t('wiki.sources.autoSyncInterval', { sec: Math.round(watcher.intervalMs / 1000) }) }}
        <template v-if="watcher.sourceType"> · {{ watcher.sourceType }}</template>
      </span>
      <span v-else-if="!watcher.globalEnabled" class="auto-sync-hint">
        {{ t('wiki.sources.autoSyncGlobalOffHint') }}
      </span>
    </div>

    <!-- ── Phase 1: Search & Filter Bar ──────────────────────────────────── -->
    <div class="filter-bar">
      <div class="search-input-wrap">
        <span class="search-icon">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        </span>
        <input
          v-model="keyword"
          class="search-input"
          :placeholder="t('wiki.searchPages')"
        />
      </div>
      <div class="filter-sep"></div>
      <div class="filter-group">
        <span class="filter-label">{{ t('wiki.sources.sourceType') }}</span>
        <select v-model="sourceTypeFilter" class="select-filter">
          <option value="all">{{ t('wiki.sources.allTypes') }}</option>
          <option v-for="st in distinctSourceTypes" :key="st" :value="st">
            {{ sourceTypeLabel(st) }}
          </option>
        </select>
      </div>
      <div class="filter-sep"></div>
      <div class="filter-group">
        <span class="filter-label">{{ t('wiki.sources.sortLabel') }}</span>
        <select v-model="sortKey" class="select-filter">
          <option value="name">{{ t('wiki.sources.sortName') }}</option>
          <option value="date">{{ t('wiki.sources.sortDate') }}</option>
          <option value="size">{{ t('wiki.sources.sortSize') }}</option>
          <option value="status">{{ t('wiki.sources.sortStatus') }}</option>
        </select>
        <button
          type="button"
          class="sort-dir-btn"
          :title="t(sortDir === 'asc' ? 'wiki.sources.sortAsc' : 'wiki.sources.sortDesc')"
          @click="sortDir = sortDir === 'asc' ? 'desc' : 'asc'"
        >{{ sortDir === 'asc' ? '↑' : '↓' }}</button>
      </div>
      <div class="filter-sep"></div>
      <div class="filter-chips">
        <span
          class="chip"
          :class="{ active: statusFilter === 'all' }"
          @click="statusFilter = 'all'"
        >{{ t('wiki.sources.all') }} {{ filteredCounts.all }}</span>
        <span
          class="chip green"
          :class="{ active: statusFilter === 'completed' }"
          @click="statusFilter = 'completed'"
        >✓ {{ t('wiki.status.completed') }} {{ filteredCounts.completed }}</span>
        <span
          class="chip blue"
          :class="{ active: statusFilter === 'processing' }"
          @click="statusFilter = 'processing'"
        >⟳ {{ t('wiki.status.processing') }} {{ filteredCounts.processing }}</span>
        <span
          class="chip yellow"
          :class="{ active: statusFilter === 'pending' }"
          @click="statusFilter = 'pending'"
        >· {{ t('wiki.status.pending') }} {{ filteredCounts.pending }}</span>
        <span
          class="chip red"
          :class="{ active: statusFilter === 'failed' }"
          @click="statusFilter = 'failed'"
        >✕ {{ t('wiki.status.failed') }} {{ filteredCounts.failed }}</span>
      </div>
      <span class="results-count">{{ t('wiki.sources.showingCount', { count: filteredMaterials.length }) }}</span>
    </div>

    <!-- Raw materials list -->
    <div class="raw-list">
      <h4 class="raw-list-title">
        {{ t('wiki.rawMaterials') }} ({{ store.rawMaterials.length + uploadingFiles.length }})
      </h4>
      <div v-if="store.rawMaterials.length === 0 && uploadingFiles.length === 0" class="empty-hint">
        {{ t('wiki.noRawMaterials') }}
      </div>

      <!-- ── Phase 3: Batch action bar ────────────────────────────────────── -->
      <div v-if="canManageWiki && selectedCount > 0" class="batch-bar">
        <span class="batch-count">{{ t('wiki.sources.selectedCount', { count: selectedCount }) }}</span>
        <div class="batch-sep"></div>
        <div class="batch-actions">
          <button class="batch-btn" @click="batchReprocess">⟳ {{ t('wiki.sources.batchReprocess') }}</button>
          <button class="batch-btn" @click="batchDownload">⬇ {{ t('wiki.sources.batchDownload') }}</button>
          <button class="batch-btn danger" @click="batchDelete">🗑 {{ t('wiki.sources.batchDelete') }}</button>
        </div>
        <span class="clear-sel" @click="clearSelection">{{ t('wiki.sources.clearSelection') }}</span>
      </div>

      <!-- ── Phase 6: Error file partition ────────────────────────────────── -->
      <div v-if="failedMaterials.length > 0" class="error-section">
        <div class="error-section-header" @click="errorSectionOpen = !errorSectionOpen">
          <span class="group-chevron" :class="{ open: errorSectionOpen }">▶</span>
          <span class="error-icon">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
          </span>
          <span class="error-title">{{ t('wiki.errorSection.title') }}</span>
          <span class="error-count">{{ failedMaterials.length }}</span>
          <span class="error-hint-text">{{ t('wiki.errorSection.hint') }}</span>
          <div v-if="canManageWiki" class="error-actions-bar" @click.stop>
            <button class="error-bar-btn" @click="retryAllErrors">⟳ {{ t('wiki.errorSection.retryAll') }}</button>
            <button class="error-bar-btn danger" @click="clearAllErrors">🗑 {{ t('wiki.errorSection.clearAll') }}</button>
          </div>
        </div>
        <div v-show="errorSectionOpen" class="error-body">
          <div v-for="raw in failedMaterials" :key="raw.id" class="error-item">
            <span class="error-file-emoji">📄</span>
            <div class="error-file-info">
              <div class="error-file-name">
                <span class="error-file-title clickable" :title="raw.title" @click="openPreview(raw)">{{ raw.title }}</span>
                <span v-if="raw.sourceType" class="error-tag">{{ sourceTypeLabel(raw.sourceType) }}</span>
              </div>
              <div class="error-msg">{{ raw.errorMessage || friendlyError(raw) }}</div>
            </div>
            <div v-if="canManageWiki" class="error-actions">
              <button class="error-item-btn" @click="openPreview(raw)">👁 {{ t('wiki.errorSection.preview') }}</button>
              <button class="error-item-btn" @click="reprocess(raw.id)">⟳ {{ t('wiki.errorSection.retry') }}</button>
              <button class="btn-icon btn-icon-danger" :title="t('common.delete')" @click="deleteRaw(raw.id)">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Optimistic uploading items shown at the top -->
      <div
        v-for="uf in uploadingFiles"
        :key="uf.tempId"
        class="raw-item raw-item--uploading"
      >
        <div class="raw-item-row">
          <div class="raw-item-info">
            <span class="raw-item-title">{{ uf.name }}</span>
          </div>
          <div class="raw-item-meta">
            <span v-if="uf.status === 'error'" class="status-badge failed">{{ t('wiki.status.failed') }}</span>
            <span v-else class="status-badge uploading">{{ t('wiki.status.uploading') }}</span>
            <span
              v-if="uf.status === 'error' && uf.errorMsg"
              class="error-hint" :title="uf.errorMsg"
            >{{ uf.errorMsg }}</span>
          </div>
          <div class="raw-item-actions">
            <!-- Dismiss error item -->
            <button
              v-if="uf.status === 'error'"
              class="btn-icon btn-icon-danger"
              :title="t('common.delete')"
              @click="removeUploadingFile(uf.tempId)"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
        </div>
        <!-- HTTP upload progress bar -->
        <div v-if="uf.status !== 'error'" class="raw-progress">
          <div class="raw-progress-track">
            <div
              class="raw-progress-fill"
              :class="{ indeterminate: uf.httpPct === 0 }"
              :style="uf.httpPct > 0 ? { width: uf.httpPct + '%' } : {}"
            ></div>
          </div>
          <span class="raw-progress-label">
            {{ uf.httpPct > 0 ? t('wiki.progress.uploading', { pct: uf.httpPct }) : t('wiki.progress.preparing') }}
          </span>
        </div>
      </div>

      <!-- No filter match -->
      <div v-if="store.rawMaterials.length > 0 && filteredMaterials.length === 0" class="empty-hint">
        {{ t('wiki.sources.noMatch') }}
      </div>

      <!-- ── Phase 2: grouped by source type ──────────────────────────────── -->
      <div v-for="group in groupedMaterials" :key="group.key" class="file-group">
        <div class="group-header" @click="toggleGroup(group.key)">
          <span class="group-chevron" :class="{ open: !isGroupCollapsed(group.key) }">▶</span>
          <span
            v-if="canManageWiki"
            class="group-checkbox"
            :class="{ checked: groupSelectState(group.items) === 'all', partial: groupSelectState(group.items) === 'partial' }"
            @click.stop="toggleGroupSelect(group.items)"
          >
            <svg width="10" height="8" viewBox="0 0 10 8"><path d="M1 4L3.5 6.5L9 1" stroke="white" stroke-width="1.5" fill="none" stroke-linecap="round"/></svg>
          </span>
          <span class="group-alias">{{ group.label }}</span>
          <div class="group-stats">
            <span class="stat-pill total">{{ group.counts.total }}</span>
            <span v-if="group.counts.completed" class="stat-pill ok">✓ {{ group.counts.completed }}</span>
            <span v-if="group.counts.processing" class="stat-pill processing">⟳ {{ group.counts.processing }}</span>
            <span v-if="group.counts.pending" class="stat-pill pending">· {{ group.counts.pending }}</span>
            <span v-if="group.counts.failed" class="stat-pill err">✕ {{ group.counts.failed }}</span>
          </div>
          <span v-if="scheduleText(group.key)" class="group-schedule" :title="t('wiki.pathConfig.schedule')">
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>
            </svg>
            {{ scheduleText(group.key) }}
          </span>
          <div v-if="canManageWiki" class="group-actions" @click.stop>
            <button class="btn-icon group-cfg-btn" :title="t('wiki.pathConfig.title')" @click="openConfig(group)">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="3"/>
                <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
              </svg>
            </button>
          </div>
        </div>
        <div v-show="!isGroupCollapsed(group.key)" class="group-body">
          <div
            v-for="raw in group.items"
            :key="raw.id"
            class="raw-item"
            :class="{ 'raw-item--active': store.selectedRawId === raw.id }"
            @click="toggleRawFilter(raw.id)"
          >
        <div class="raw-item-row">
          <span
            v-if="canManageWiki"
            class="row-checkbox"
            :class="{ checked: isSelected(raw.id) }"
            @click.stop="toggleRowSelect(raw.id)"
          >
            <svg width="10" height="8" viewBox="0 0 10 8"><path d="M1 4L3.5 6.5L9 1" stroke="white" stroke-width="1.5" fill="none" stroke-linecap="round"/></svg>
          </span>
          <div class="raw-item-info">
            <span class="raw-item-title clickable" :title="raw.title" @click.stop="openPreview(raw)">{{ raw.title }}</span>
            <span class="raw-item-type">{{ raw.sourceType }}</span>
          </div>
          <div class="raw-item-meta">
            <span
              class="status-badge"
              :class="cancellingIds.has(raw.id) && raw.processingStatus === 'processing' ? 'cancelling' : raw.processingStatus"
            >
              {{ cancellingIds.has(raw.id) && raw.processingStatus === 'processing'
                ? t('wiki.status.cancelling')
                : t(`wiki.status.${raw.processingStatus}`) }}
            </span>
            <span v-if="raw.pageCount != null && raw.pageCount > 0" class="page-count-chip">
              <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
              {{ raw.pageCount }}
            </span>
            <span
              v-if="raw.processingStatus === 'cancelled'"
              class="error-hint" :title="raw.errorMessage || ''"
            >
              {{ t('wiki.cancelledHint') }}
            </span>
            <span
              v-else-if="(raw.errorCode || raw.errorMessage) && (raw.processingStatus === 'failed' || raw.processingStatus === 'partial')"
              class="error-hint" :title="raw.errorMessage || friendlyError(raw)"
            >
              {{ friendlyError(raw) }}
            </span>
            <span
              v-if="(raw.warningCode || raw.warningMessage) && raw.processingStatus !== 'failed'"
              class="warning-hint" :title="raw.warningMessage || friendlyWarning(raw)"
            >
              ⚠ {{ friendlyWarning(raw) }}
            </span>
          </div>
          <div v-if="canManageWiki" class="raw-item-actions">
            <button
              v-if="raw.processingStatus === 'processing' && !cancellingIds.has(raw.id)"
              class="btn-icon btn-icon-danger" :title="t('wiki.cancel')"
              @click="cancelRaw(raw.id)"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
                <line x1="6" y1="6" x2="18" y2="18"/>
                <line x1="6" y1="18" x2="18" y2="6"/>
              </svg>
            </button>
            <button
              v-else-if="raw.processingStatus === 'processing' && cancellingIds.has(raw.id)"
              class="btn-icon btn-icon-cancelling" :title="t('wiki.cancelling')" disabled
            >
              <svg class="spinner" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
                <path d="M21 12a9 9 0 1 1-6.22-8.56"/>
              </svg>
            </button>
            <button
              v-else-if="raw.processingStatus === 'partial'"
              class="btn-icon btn-icon-resume" :title="t('wiki.resume')"
              @click="reprocess(raw.id)"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round">
                <polygon points="6 4 20 12 6 20 6 4"/>
              </svg>
            </button>
            <button
              v-else-if="raw.processingStatus === 'failed' || raw.processingStatus === 'completed' || raw.processingStatus === 'cancelled'"
              class="btn-icon" :title="t('wiki.reprocess')"
              @click="reprocess(raw.id)"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="23 4 23 10 17 10"/>
                <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
              </svg>
            </button>
            <button
              v-if="raw.processingStatus !== 'uploading'"
              class="btn-icon" :title="t('wiki.download')"
              @click="downloadRaw(raw)"
            >
              <el-icon :size="14"><Download /></el-icon>
            </button>
            <button class="btn-icon btn-icon-danger" :title="t('common.delete')" @click="deleteRaw(raw.id)">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="3 6 5 6 21 6"/>
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
              </svg>
            </button>
          </div>
        </div>
        <!-- RFC-033: Job stage bar — show when job has progressed past 'queued' or reached terminal -->
        <JobStageBar
          v-if="rawJobs[raw.id] && (rawJobs[raw.id].stage !== 'queued' || rawJobs[raw.id].status !== 'queued')"
          :stage="rawJobs[raw.id].stage"
          :status="rawJobs[raw.id].status"
          :current-model="rawJobs[raw.id].currentModelName ?? (rawJobs[raw.id].currentModelId ? `Model #${rawJobs[raw.id].currentModelId}` : undefined)"
          :is-fallback-active="rawJobs[raw.id].currentModelId != null && rawJobs[raw.id].currentModelId !== rawJobs[raw.id].primaryModelId"
          :error-code="rawJobs[raw.id].errorCode ?? undefined"
          :error-message="rawJobs[raw.id].errorMessage ?? undefined"
          :done="rawJobs[raw.id].done ?? raw.progressDone"
          :total="rawJobs[raw.id].total ?? raw.progressTotal"
          :started-at="rawJobs[raw.id].startedAt ?? undefined"
          @reprocess="reprocess(raw.id)"
          @repair="handleLocalRepair(raw.id)"
        />
        <!-- Original progress bar: shown during processing when job data is not active -->
        <div v-else-if="raw.processingStatus === 'processing'" class="raw-progress">
          <div class="raw-progress-track">
            <div
              class="raw-progress-fill"
              :class="{ indeterminate: !raw.progressTotal }"
              :style="raw.progressTotal
                ? { width: Math.min(100, Math.round((raw.progressDone / raw.progressTotal) * 100)) + '%' }
                : {}"
            ></div>
          </div>
          <span class="raw-progress-label">
            {{ raw.progressTotal
              ? `${raw.progressDone} / ${raw.progressTotal}`
              : t('wiki.progress.preparing') }}
          </span>
        </div>
          </div>
          <div class="group-footer">
            <span>{{ t('wiki.sources.groupCount', { count: group.items.length }) }}</span>
            <span v-if="group.lastTime" class="group-footer-time">
              {{ t('wiki.sources.lastScan', { time: formatScanTime(group.lastTime) }) }}
            </span>
          </div>
        </div>
      </div>

      <!-- ── Phase 4: Add new scan path ───────────────────────────────────── -->
      <div v-if="canManageWiki" class="add-path-card" @click="openAddPath">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
        {{ t('wiki.pathConfig.addCard') }}
      </div>
    </div>

    <!-- Process all button -->
    <button
      v-if="store.currentKB && store.rawMaterials.some(r => r.processingStatus === 'pending')"
      class="btn-primary process-btn"
      @click="processAll"
    >
      {{ t('wiki.processAll') }}
    </button>

    <!-- Add Text Modal -->
    <div v-if="showAddText" class="modal-overlay">
      <div class="modal-content">
        <h3 class="modal-title">{{ t('wiki.addText') }}</h3>
        <div class="form-group">
          <label>{{ t('wiki.materialTitle') }}</label>
          <input v-model="textTitle" type="text" class="form-input" />
        </div>
        <div class="form-group">
          <label>{{ t('wiki.materialContent') }}</label>
          <textarea v-model="textContent" class="form-input" rows="12" :placeholder="t('wiki.pasteContent')"></textarea>
        </div>
        <div class="modal-actions">
          <button class="btn-secondary" @click="showAddText = false">{{ t('common.cancel') }}</button>
          <button class="btn-primary" @click="handleAddText" :disabled="!textTitle.trim() || !textContent.trim()">
            {{ t('common.add') }}
          </button>
        </div>
      </div>
    </div>

    <!-- ── Phase 4: Path config / add path modal ──────────────────────────── -->
    <div v-if="pathModalOpen" class="modal-overlay" @click.self="pathModalOpen = false">
      <div class="modal-content path-modal">
        <h3 class="modal-title">
          {{ pathModalMode === 'add'
            ? t('wiki.pathConfig.addTitle')
            : t('wiki.pathConfig.titleWith', { name: configForm.alias || t('wiki.pathConfig.title') }) }}
        </h3>
        <p class="frontend-only-hint">⚠ {{ t('wiki.pathConfig.frontendOnlyHint') }}</p>

        <div class="form-group">
          <label>{{ t('wiki.pathConfig.alias') }}</label>
          <input v-model="configForm.alias" class="form-input" :placeholder="t('wiki.pathConfig.aliasPlaceholder')" />
          <span class="form-hint">{{ t('wiki.pathConfig.aliasHint') }}</span>
        </div>
        <div class="form-group">
          <label>{{ t('wiki.pathConfig.path') }}</label>
          <input v-model="configForm.path" class="form-input" :placeholder="t('wiki.pathConfig.pathPlaceholder')" />
          <span class="form-hint">{{ t('wiki.pathConfig.pathHint') }}</span>
        </div>
        <div class="form-group">
          <label>{{ t('wiki.pathConfig.fileFilter') }}</label>
          <input v-model="configForm.fileFilter" class="form-input" :placeholder="t('wiki.pathConfig.fileFilterPlaceholder')" />
        </div>
        <div class="form-group">
          <label>{{ t('wiki.pathConfig.schedule') }}</label>
          <div class="schedule-config-row">
            <label class="toggle-switch">
              <input type="checkbox" v-model="configForm.scheduleEnabled" />
              <span class="toggle-track"></span>
              <span class="toggle-thumb"></span>
            </label>
            <span class="schedule-state" :class="{ on: configForm.scheduleEnabled }">
              {{ configForm.scheduleEnabled ? t('wiki.pathConfig.scheduleOn') : t('wiki.pathConfig.scheduleOff') }}
            </span>
          </div>
          <select v-if="configForm.scheduleEnabled" v-model="configForm.cron" class="select-filter cron-select">
            <option v-for="c in CRON_OPTIONS" :key="c" :value="c">{{ cronLabel(c) }}</option>
          </select>
          <input
            v-if="configForm.scheduleEnabled && configForm.cron === 'custom'"
            v-model="configForm.customCron"
            class="form-input cron-custom-input"
            :placeholder="t('wiki.pathConfig.cronCustomPlaceholder')"
          />
        </div>
        <div class="form-group">
          <label>{{ t('wiki.pathConfig.scanMode') }}</label>
          <div class="mode-selector">
            <label class="mode-option" :class="{ selected: configForm.scanMode === 'incr' }">
              <input type="radio" value="incr" v-model="configForm.scanMode" />
              <span class="mode-option-text">
                <span class="mode-option-title">{{ t('wiki.pathConfig.modeIncr') }}</span>
                <span class="mode-option-hint">{{ t('wiki.pathConfig.modeIncrHint') }}</span>
              </span>
            </label>
            <label class="mode-option" :class="{ selected: configForm.scanMode === 'full' }">
              <input type="radio" value="full" v-model="configForm.scanMode" />
              <span class="mode-option-text">
                <span class="mode-option-title">{{ t('wiki.pathConfig.modeFull') }}</span>
                <span class="mode-option-hint">{{ t('wiki.pathConfig.modeFullHint') }}</span>
              </span>
            </label>
          </div>
        </div>

        <div class="modal-actions path-modal-actions">
          <button
            v-if="pathModalMode === 'edit' && configEditingIsCustom"
            class="btn-remove-path"
            @click="removePath"
          >{{ t('wiki.pathConfig.removePath') }}</button>
          <div class="path-modal-actions-right">
            <button class="btn-secondary" @click="pathModalOpen = false">{{ t('common.cancel') }}</button>
            <button class="btn-primary" @click="pathModalMode === 'add' ? confirmAddPath() : saveConfig()">
              {{ pathModalMode === 'add' ? t('wiki.pathConfig.addConfirm') : t('wiki.pathConfig.save') }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- ── Phase 5: File preview modal ────────────────────────────────────── -->
    <div v-if="previewOpen && previewRaw" class="modal-overlay" @click.self="previewOpen = false">
      <div class="modal-content preview-modal">
        <div class="preview-header">
          <span class="preview-title" :title="previewRaw.title">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
            {{ previewRaw.title }}
          </span>
          <div class="preview-header-actions">
            <button class="btn-secondary preview-dl-btn" @click="downloadRaw(previewRaw)">
              <el-icon :size="13"><Download /></el-icon>
              {{ t('wiki.preview.download') }}
            </button>
            <button class="btn-icon" :title="t('wiki.preview.close')" @click="previewOpen = false">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
          </div>
        </div>
        <div class="preview-meta">
          <span class="preview-meta-item">{{ t('wiki.preview.group') }}: {{ groupLabel(previewRaw.sourceType || UNGROUPED_KEY) }}</span>
          <span class="preview-meta-item">{{ t('wiki.preview.size') }}: {{ formatSize(previewRaw.fileSize) }}</span>
          <span class="preview-meta-item">{{ t('wiki.preview.scanTime') }}: {{ formatScanTime(previewRaw.createTime) || '—' }}</span>
          <span class="status-badge" :class="previewRaw.processingStatus">{{ t(`wiki.status.${previewRaw.processingStatus}`) }}</span>
          <span v-if="previewRaw.pageCount != null && previewRaw.pageCount > 0" class="preview-meta-item">
            {{ t('wiki.preview.pages', { count: previewRaw.pageCount }) }}
          </span>
        </div>
        <div class="preview-body">
          <div v-if="previewIsBinary" class="preview-placeholder">{{ t('wiki.preview.binaryHint') }}</div>
          <div v-else-if="previewLoading" class="preview-placeholder">{{ t('wiki.preview.loading') }}</div>
          <div v-else-if="previewError" class="preview-placeholder">{{ t('wiki.preview.loadFailed') }}</div>
          <pre v-else-if="previewText" class="preview-content">{{ previewText }}</pre>
          <div v-else class="preview-placeholder">{{ t('wiki.preview.empty') }}</div>
        </div>
        <div class="modal-actions">
          <button class="btn-secondary" @click="previewOpen = false">{{ t('wiki.preview.close') }}</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { useFileDrop } from '@/composables/useFileDrop'
import { Download } from '@element-plus/icons-vue'
import { useWikiStore } from '@/stores/useWikiStore'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { wikiApi } from '@/api/index'
import { mcConfirm } from '@/components/common/useConfirm'
import JobStageBar from './JobStageBar.vue'
import type { WikiProcessingJob } from '@/composables/useWikiJobPoller'

const { t } = useI18n()
const store = useWikiStore()
const workspace = useWorkspaceStore()

// Uploading, scanning and (re)processing raw material all require manage:wiki.
// Viewers can still see the material list but get no write controls.
const canManageWiki = computed(() => workspace.can('manage:wiki'))
const fileInput = ref<HTMLInputElement | null>(null)

// ── Phase 1: Search & Filter Bar ─────────────────────────────────────────
const keyword = ref('')
const sourceTypeFilter = ref('all')
const sortKey = ref('name')
const sortDir = ref<'asc' | 'desc'>('asc')
const statusFilter = ref('all')

/** Unique sourceType values from all materials, sorted, for the dropdown. */
const distinctSourceTypes = computed(() => {
  const set = new Set<string>()
  for (const r of store.rawMaterials) {
    if (r.sourceType) set.add(r.sourceType)
  }
  return Array.from(set).sort()
})

/** Map a raw sourceType value to a human-readable label. */
function sourceTypeLabel(st: string): string {
  const key = `wiki.sourceType.${st}`
  const label = t(key)
  return label !== key ? label : st
}

/** Count of materials per status (before filtering by keyword/sourceType). */
const filteredCounts = computed(() => {
  const counts = { all: 0, completed: 0, processing: 0, pending: 0, failed: 0 }
  for (const r of store.rawMaterials) {
    counts.all++
    if (r.processingStatus === 'completed' || r.processingStatus === 'partial') counts.completed++
    else if (r.processingStatus === 'processing') counts.processing++
    else if (r.processingStatus === 'pending') counts.pending++
    else if (r.processingStatus === 'failed' || r.processingStatus === 'cancelled') counts.failed++
  }
  return counts
})

/** Client-side filtered + sorted materials. */
const filteredMaterials = computed(() => {
  let list = store.rawMaterials

  // Keyword filter
  const kw = keyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter(r => r.title.toLowerCase().includes(kw))
  }

  // Source type filter
  if (sourceTypeFilter.value !== 'all') {
    list = list.filter(r => r.sourceType === sourceTypeFilter.value)
  }

  // Status filter
  const sf = statusFilter.value
  if (sf !== 'all') {
    if (sf === 'completed') {
      list = list.filter(r => r.processingStatus === 'completed' || r.processingStatus === 'partial')
    } else if (sf === 'failed') {
      list = list.filter(r => r.processingStatus === 'failed' || r.processingStatus === 'cancelled')
    } else {
      list = list.filter(r => r.processingStatus === sf)
    }
  }

  // Sort (ascending base comparators; reverse for descending)
  const sorted = [...list]
  switch (sortKey.value) {
    case 'name':
      sorted.sort((a, b) => a.title.localeCompare(b.title, 'zh-CN'))
      break
    case 'date':
      sorted.sort((a, b) => (a.createTime || '').localeCompare(b.createTime || ''))
      break
    case 'size':
      sorted.sort((a, b) => (a.fileSize || 0) - (b.fileSize || 0))
      break
    case 'status': {
      const order: Record<string, number> = { processing: 0, pending: 1, partial: 2, completed: 3, failed: 4, cancelled: 5 }
      sorted.sort((a, b) => (order[a.processingStatus] ?? 99) - (order[b.processingStatus] ?? 99))
      break
    }
  }
  if (sortDir.value === 'desc') sorted.reverse()

  return sorted
})

// ── Phase 2: Grouping by sourceType ──────────────────────────────────────
type RawItem = (typeof store.rawMaterials)[number]

const UNGROUPED_KEY = '__ungrouped__'
// Fixed display order for known source types; unknown types sort after these,
// and the ungrouped bucket always sinks to the bottom.
const GROUP_ORDER = ['UPLOAD', 'TEXT', 'SCAN']

// Collapsed group keys. The ungrouped bucket now holds all manual materials, so
// it starts expanded.
const collapsedGroups = ref(new Set<string>())

function isGroupCollapsed(key: string): boolean {
  return collapsedGroups.value.has(key)
}
function toggleGroup(key: string) {
  const next = new Set(collapsedGroups.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsedGroups.value = next
}

function groupLabel(key: string): string {
  const alias = pathConfigs.value[key]?.alias
  if (alias) return alias
  if (key === UNGROUPED_KEY) return t('wiki.sources.ungrouped')
  if (key.startsWith('custom:')) return t('wiki.pathConfig.untitledPath')
  return sourceTypeLabel(key)
}

function formatScanTime(s: string): string {
  return s ? s.replace('T', ' ').slice(0, 16) : ''
}

/** Group filtered+sorted materials by sourceType, preserving the active sort. */
const groupedMaterials = computed(() => {
  const map = new Map<string, RawItem[]>()
  for (const raw of filteredMaterials.value) {
    // Manual uploads/pasted text all land in one "未分组文档" bucket regardless of
    // file type; only user-created scan-path groups get their own group.
    const key = UNGROUPED_KEY
    const bucket = map.get(key)
    if (bucket) bucket.push(raw)
    else map.set(key, [raw])
  }

  // Frontend-only custom scan paths render as (possibly empty) groups.
  for (const ck of customGroupKeys.value) {
    if (!map.has(ck)) map.set(ck, [])
  }

  const rank = (k: string) => {
    const i = GROUP_ORDER.indexOf(k)
    if (i !== -1) return i
    return k === UNGROUPED_KEY ? 999 : 500
  }
  const keys = Array.from(map.keys()).sort((a, b) => {
    const ra = rank(a)
    const rb = rank(b)
    return ra !== rb ? ra - rb : a.localeCompare(b)
  })

  return keys.map(key => {
    const items = map.get(key)!
    const counts = { total: items.length, completed: 0, processing: 0, pending: 0, failed: 0 }
    let lastTime = ''
    for (const r of items) {
      if (r.processingStatus === 'completed' || r.processingStatus === 'partial') counts.completed++
      else if (r.processingStatus === 'processing') counts.processing++
      else if (r.processingStatus === 'pending') counts.pending++
      else if (r.processingStatus === 'failed' || r.processingStatus === 'cancelled') counts.failed++
      if (r.createTime && r.createTime > lastTime) lastTime = r.createTime
    }
    return { key, label: groupLabel(key), items, counts, lastTime }
  })
})

// ── Phase 3: Multi-select & batch actions ────────────────────────────────
const selectedIds = ref(new Set<number>())
const selectedCount = computed(() => selectedIds.value.size)

function isSelected(id: number): boolean {
  return selectedIds.value.has(id)
}
function toggleRowSelect(id: number) {
  const next = new Set(selectedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedIds.value = next
}
function clearSelection() {
  if (selectedIds.value.size) selectedIds.value = new Set()
}

// Tri-state for a group's select-all checkbox.
function groupSelectState(items: RawItem[]): 'all' | 'partial' | 'none' {
  let sel = 0
  for (const r of items) if (selectedIds.value.has(r.id)) sel++
  if (sel === 0) return 'none'
  return sel === items.length ? 'all' : 'partial'
}
function toggleGroupSelect(items: RawItem[]) {
  const next = new Set(selectedIds.value)
  const allSelected = items.every(r => next.has(r.id))
  for (const r of items) {
    if (allSelected) next.delete(r.id)
    else next.add(r.id)
  }
  selectedIds.value = next
}

// Keep the selection in sync with what's visible: when filters hide rows or a
// material is deleted, drop those ids so the count never lies.
watch(filteredMaterials, (list) => {
  if (selectedIds.value.size === 0) return
  const visible = new Set(list.map(r => r.id))
  const next = new Set<number>()
  for (const id of selectedIds.value) if (visible.has(id)) next.add(id)
  if (next.size !== selectedIds.value.size) selectedIds.value = next
})

const selectedRaws = computed(() => store.rawMaterials.filter(r => selectedIds.value.has(r.id)))

async function batchReprocess() {
  const ids = selectedRaws.value
    .filter(r => r.processingStatus !== 'processing' && r.processingStatus !== 'uploading')
    .map(r => r.id)
  for (const id of ids) {
    try { await reprocess(id) } catch { /* skip failures, continue the batch */ }
  }
  clearSelection()
}

async function batchDownload() {
  for (const r of selectedRaws.value) {
    if (r.processingStatus === 'uploading') continue
    await downloadRaw(r)
  }
}

async function batchDelete() {
  const count = selectedIds.value.size
  if (count === 0 || !store.currentKB) return
  const ok = await mcConfirm({
    title: t('wiki.sources.batchDelete'),
    message: t('wiki.sources.batchDeleteConfirm', { count }),
    tone: 'danger',
  })
  if (!ok) return
  const kbId = store.currentKB.id
  for (const id of Array.from(selectedIds.value)) {
    try { await wikiApi.deleteRaw(kbId, id) } catch { /* skip failures, continue the batch */ }
  }
  clearSelection()
  await store.fetchRawMaterials(kbId)
}

// ── Phase 4: Per-group path config (frontend-only, localStorage) ──────────
// NOTE: the backend currently has no multi-path / per-path schedule / scan-mode
// API. This config is persisted in localStorage as a UI-first prototype; only
// alias and grouping affect what the user sees today. Wire to a real API later.
type ScanMode = 'incr' | 'full'
interface PathConfig {
  alias: string
  path: string
  fileFilter: string
  scheduleEnabled: boolean
  cron: string
  customCron: string
  scanMode: ScanMode
}

const CRON_OPTIONS = ['hourly', 'daily02', 'daily06', 'daily10', 'daily22', 'weekly1', 'custom']
function defaultConfig(): PathConfig {
  return { alias: '', path: '', fileFilter: '', scheduleEnabled: false, cron: 'daily02', customCron: '', scanMode: 'incr' }
}
function cronLabel(v: string): string {
  return t(`wiki.pathConfig.cron_${v}`)
}

/** Human-readable schedule summary for a group's header chip; '' when off. */
function scheduleText(key: string): string {
  const cfg = pathConfigs.value[key]
  if (!cfg?.scheduleEnabled) return ''
  if (cfg.cron === 'custom') return cfg.customCron || t('wiki.pathConfig.cron_custom')
  return cronLabel(cfg.cron)
}

const pathConfigs = ref<Record<string, PathConfig>>({})
const customGroupKeys = ref<string[]>([])

function pathStorageKey(kbId: number): string {
  return `wiki:rawcfg:${kbId}`
}
function loadPathConfigs(kbId: number) {
  pathConfigs.value = {}
  customGroupKeys.value = []
  try {
    const raw = localStorage.getItem(pathStorageKey(kbId))
    if (raw) {
      const data = JSON.parse(raw)
      pathConfigs.value = data.configs || {}
      customGroupKeys.value = data.custom || []
    }
  } catch { /* ignore corrupt cache */ }
}
function savePathConfigs() {
  if (!store.currentKB) return
  try {
    localStorage.setItem(pathStorageKey(store.currentKB.id), JSON.stringify({
      configs: pathConfigs.value,
      custom: customGroupKeys.value,
    }))
  } catch { /* ignore quota errors */ }
}

const pathModalOpen = ref(false)
const pathModalMode = ref<'edit' | 'add'>('edit')
const configForm = ref<PathConfig>(defaultConfig())
const configEditingKey = ref<string | null>(null)
const configEditingIsCustom = ref(false)

function openConfig(group: { key: string; label: string }) {
  const key = group.key
  configEditingKey.value = key
  configEditingIsCustom.value = key.startsWith('custom:')
  pathModalMode.value = 'edit'
  const stored = pathConfigs.value[key]
  const base = defaultConfig()
  base.alias = stored?.alias || (key.startsWith('custom:') ? '' : group.label)
  base.path = stored?.path || (key === 'SCAN' ? (store.currentKB?.sourceDirectory || '') : '')
  base.fileFilter = stored?.fileFilter || ''
  base.scheduleEnabled = stored?.scheduleEnabled ?? false
  base.cron = stored?.cron || 'daily02'
  base.customCron = stored?.customCron || ''
  base.scanMode = stored?.scanMode || 'incr'
  configForm.value = base
  pathModalOpen.value = true
}
function openAddPath() {
  configEditingKey.value = null
  configEditingIsCustom.value = false
  pathModalMode.value = 'add'
  configForm.value = defaultConfig()
  pathModalOpen.value = true
}
function saveConfig() {
  const key = configEditingKey.value
  if (!key) return
  pathConfigs.value = { ...pathConfigs.value, [key]: { ...configForm.value } }
  savePathConfigs()
  pathModalOpen.value = false
}
function confirmAddPath() {
  const key = `custom:${Date.now()}`
  customGroupKeys.value = [...customGroupKeys.value, key]
  pathConfigs.value = { ...pathConfigs.value, [key]: { ...configForm.value } }
  savePathConfigs()
  pathModalOpen.value = false
}
async function removePath() {
  const key = configEditingKey.value
  if (!key) return
  const ok = await mcConfirm({
    title: t('wiki.pathConfig.removePath'),
    message: t('wiki.pathConfig.removeConfirm'),
    tone: 'danger',
  })
  if (!ok) return
  const cfgs = { ...pathConfigs.value }
  delete cfgs[key]
  pathConfigs.value = cfgs
  if (key.startsWith('custom:')) {
    customGroupKeys.value = customGroupKeys.value.filter(k => k !== key)
  }
  savePathConfigs()
  pathModalOpen.value = false
}

// ── Phase 5: File preview modal ───────────────────────────────────────────
// Text-like extensions are fetched and rendered inline; everything else shows
// a "download to view" hint. Pasted text (sourceType TEXT) is always text.
const TEXT_EXTENSIONS = new Set(['txt', 'md', 'markdown', 'csv', 'tsv', 'html', 'htm', 'json', 'log', 'xml', 'yaml', 'yml'])

const previewOpen = ref(false)
const previewRaw = ref<RawItem | null>(null)
const previewLoading = ref(false)
const previewText = ref('')
const previewIsBinary = ref(false)
const previewError = ref(false)

function formatSize(bytes?: number): string {
  if (!bytes || bytes <= 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function extOf(title: string): string {
  const i = title.lastIndexOf('.')
  return i >= 0 ? title.slice(i + 1).toLowerCase() : ''
}

function isTextLike(raw: RawItem): boolean {
  if (raw.sourceType === 'TEXT') return true
  return TEXT_EXTENSIONS.has(extOf(raw.title))
}

async function openPreview(raw: RawItem) {
  previewRaw.value = raw
  previewText.value = ''
  previewError.value = false
  previewOpen.value = true
  if (!isTextLike(raw)) {
    previewIsBinary.value = true
    return
  }
  previewIsBinary.value = false
  if (!store.currentKB) return
  previewLoading.value = true
  try {
    const blob = (await wikiApi.downloadRaw(store.currentKB.id, raw.id)) as unknown as Blob
    previewText.value = await blob.text()
  } catch {
    previewError.value = true
  } finally {
    previewLoading.value = false
  }
}

// ── Phase 6: Error file partition ─────────────────────────────────────────
// Failed materials are surfaced in a dedicated red-themed section for triage,
// in addition to appearing in their normal source group.
const failedMaterials = computed(() => store.rawMaterials.filter(r => r.processingStatus === 'failed'))
const errorSectionOpen = ref(false)

async function retryAllErrors() {
  for (const r of failedMaterials.value) {
    try { await reprocess(r.id) } catch { /* skip failures, continue the batch */ }
  }
}

async function clearAllErrors() {
  const ids = failedMaterials.value.map(r => r.id)
  if (ids.length === 0 || !store.currentKB) return
  const ok = await mcConfirm({
    title: t('wiki.errorSection.clearAll'),
    message: t('wiki.errorSection.clearConfirm', { count: ids.length }),
    tone: 'danger',
  })
  if (!ok) return
  const kbId = store.currentKB.id
  for (const id of ids) {
    try { await wikiApi.deleteRaw(kbId, id) } catch { /* skip failures, continue the batch */ }
  }
  await store.fetchRawMaterials(kbId)
}

// Map a structured backend errorCode to a localized, user-friendly hint.
// Falls back to the raw backend message, then to a generic failure label, so
// the user always sees something meaningful — never a blank "failed" badge.
function friendlyError(raw: { errorCode?: string | null; errorMessage?: string | null }): string {
  const code = raw.errorCode
  if (code) {
    const key = `wiki.errorCode.${code}`
    const msg = t(key)
    if (msg !== key) return msg
  }
  return raw.errorMessage || t('wiki.errorCode.UNKNOWN')
}

// Same idea for the non-blocking warning surface (degraded-but-usable rows).
function friendlyWarning(raw: { warningCode?: string | null; warningMessage?: string | null }): string {
  const code = raw.warningCode
  if (code) {
    const key = `wiki.warningCode.${code}`
    const msg = t(key)
    if (msg !== key) return msg
  }
  return raw.warningMessage || t('wiki.warningCode.UNKNOWN')
}

// While raw materials are active, subscribe to the backend SSE progress stream.
// A slower polling fallback keeps the UI in sync if SSE reconnects or misses a
// terminal event. The database remains the source of truth.
let sse: EventSource | null = null
let fallbackTimer: number | null = null
let activeKbId: number | null = null

const hasProcessing = computed(() =>
  store.rawMaterials.some(r => r.processingStatus === 'processing' || r.processingStatus === 'pending')
)

function applyProgressEvent(payload: any) {
  if (!payload || payload.rawId == null) return
  const raw = store.rawMaterials.find(r => r.id === payload.rawId)
  if (!raw) return
  if (typeof payload.done === 'number') raw.progressDone = payload.done
  if (typeof payload.total === 'number') raw.progressTotal = payload.total
}

function openSse(kbId: number) {
  closeSse()
  activeKbId = kbId
  // Vite proxies /api to the backend, so EventSource can use a relative URL.
  const es = new EventSource(`/api/v1/wiki/knowledge-bases/${kbId}/progress`)
  sse = es

  es.addEventListener('raw.started', (ev: MessageEvent) => {
    try {
      const data = JSON.parse(ev.data)
      const raw = store.rawMaterials.find(r => r.id === data.rawId)
      if (raw) {
        raw.processingStatus = 'processing'
        raw.progressDone = 0
        raw.progressTotal = 0
      }
    } catch { /* ignore */ }
  })
  es.addEventListener('route.done', (ev: MessageEvent) => {
    try { applyProgressEvent(JSON.parse(ev.data)) } catch { /* ignore */ }
  })
  es.addEventListener('chunk.done', (ev: MessageEvent) => {
    try { applyProgressEvent(JSON.parse(ev.data)) } catch { /* ignore */ }
  })
  es.addEventListener('raw.completed', (ev: MessageEvent) => {
    try {
      const data = JSON.parse(ev.data)
      const raw = store.rawMaterials.find(r => r.id === data.rawId)
      if (raw) {
        raw.processingStatus = data.status === 'partial' ? 'partial' : 'completed'
        if (typeof data.totalPages === 'number') {
          raw.progressDone = data.totalPages
          raw.progressTotal = data.totalPages
        }
      }
      // Clear stale job entry so JobStageBar hides
      delete rawJobs[data.rawId]
      if (store.currentKB) void store.refreshCurrentKB()
    } catch { /* ignore */ }
  })
  es.addEventListener('raw.failed', (ev: MessageEvent) => {
    try {
      const data = JSON.parse(ev.data)
      const raw = store.rawMaterials.find(r => r.id === data.rawId)
      if (raw) {
        raw.processingStatus = 'failed'
        // Surface the failure immediately from the event payload instead of
        // waiting for the refresh round-trip — and never drop it: a null
        // message would otherwise leave the user with a blank "failed" badge.
        if (typeof data.error === 'string') raw.errorMessage = data.error
        if (typeof data.errorCode === 'string') raw.errorCode = data.errorCode
      }
      // Clear stale job entry
      delete rawJobs[data.rawId]
      if (store.currentKB) void store.refreshCurrentKB()
    } catch { /* ignore */ }
  })
  es.addEventListener('raw.warning', (ev: MessageEvent) => {
    try {
      const data = JSON.parse(ev.data)
      const raw = store.rawMaterials.find(r => r.id === data.rawId)
      // A warning lands async after the material already completed, so the
      // refresh round-trip on raw.completed has already happened — apply it
      // live here, otherwise it would only appear on the next manual reload.
      if (raw) {
        if (typeof data.warning === 'string') raw.warningMessage = data.warning
        if (typeof data.warningCode === 'string') raw.warningCode = data.warningCode
      }
    } catch { /* ignore */ }
  })
  es.onerror = () => {
    // Browser EventSource auto-reconnects; just log
    // console.debug('Wiki SSE error/reconnect', kbId)
  }
}

function closeSse() {
  if (sse) {
    sse.close()
    sse = null
  }
  activeKbId = null
}

watch(
  () => [hasProcessing.value, store.currentKB?.id] as const,
  ([active, kbId]) => {
    if (active && kbId != null) {
      // SSE main channel
      if (activeKbId !== kbId) openSse(kbId)
      // 60s fallback polling
      if (fallbackTimer == null) {
        fallbackTimer = window.setInterval(() => {
          if (store.currentKB) void store.refreshCurrentKB()
        }, 60000)
      }
    } else {
      closeSse()
      if (fallbackTimer != null) {
        clearInterval(fallbackTimer)
        fallbackTimer = null
      }
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  closeSse()
  if (fallbackTimer != null) {
    clearInterval(fallbackTimer)
    fallbackTimer = null
  }
  // Stop the per-raw job poller too. Without this the setTimeout chain keeps
  // running after the panel unmounts (e.g. switching to the config tab while a
  // raw is still processing), calling refreshCurrentKB() every 3s and snapping
  // the user back to this tab.
  if (jobPoller != null) {
    clearTimeout(jobPoller)
    jobPoller = null
  }
})

// RFC-033: Job polling per raw material
const rawJobs = reactive<Record<number, WikiProcessingJob>>({})
let jobPoller: ReturnType<typeof setTimeout> | null = null

const TERMINAL_STATUSES = new Set(['completed', 'failed', 'partial', 'cancelled'])

// Local optimistic state: rows the user has just clicked "cancel" on.
// Backend cancellation is observed at the next abort checkpoint (which can
// take 10+ seconds while a route-phase LLM call is in flight), so without
// this set the click looks unresponsive — the button stays the same and
// the badge keeps reading "处理中". Cleared as soon as the row's status
// transitions out of "processing" via the next fetchRawMaterials.
const cancellingIds = ref(new Set<number>())

async function pollJobs() {
  if (!store.currentKB) return
  const kbId = store.currentKB.id
  const processingRaws = store.rawMaterials.filter(
    r => r.processingStatus === 'processing' || r.processingStatus === 'pending'
  )
  let anyTerminal = false
  for (const raw of processingRaws) {
    try {
      const res: any = await wikiApi.getWikiJobs(kbId, raw.id)
      const list = res.data || res || []
      if (list.length > 0) {
        const job = list[0]
        rawJobs[raw.id] = job
        if (TERMINAL_STATUSES.has(job.status)) {
          anyTerminal = true
        }
      }
    } catch { /* ignore */ }
  }
  // When any job reaches terminal, refresh wiki metadata, pages, and raw badges.
  if (anyTerminal) {
    await store.refreshCurrentKB()
  }
  // Continue polling while there are still processing/pending raws
  const stillActive = store.rawMaterials.some(
    r => r.processingStatus === 'processing' || r.processingStatus === 'pending'
  )
  if (stillActive) {
    jobPoller = setTimeout(pollJobs, 3000)
  }
}

watch(hasProcessing, (active) => {
  if (active) pollJobs()
  else if (jobPoller) { clearTimeout(jobPoller); jobPoller = null }
}, { immediate: true })

// Clear the optimistic "cancelling" flag for any row that has left the
// 'processing' state — the backend has written the terminal status, so
// the badge and action buttons can now reflect reality.
watch(() => store.rawMaterials, (rows) => {
  if (cancellingIds.value.size === 0) return
  const next = new Set(cancellingIds.value)
  for (const r of rows) {
    if (r.processingStatus !== 'processing' && next.has(r.id)) {
      next.delete(r.id)
    }
  }
  if (next.size !== cancellingIds.value.size) {
    cancellingIds.value = next
  }
}, { deep: true })

async function handleLocalRepair(rawId: number) {
  if (!store.currentKB) return
  // For local repair, we'd need a page slug. For now, reprocess the raw material.
  await reprocess(rawId)
}

const showAddText = ref(false)
const textTitle = ref('')
const textContent = ref('')

// ─── Per-KB auto-sync (source watcher) ────────────────────────────────────────
// Auto-sync periodically scans the directory above. It runs only when the
// server-global master switch (watcher.globalEnabled, ops-controlled) AND this
// KB's toggle (watcher.kbEnabled) are both on. When the global switch is off the
// toggle is disabled with a hint — there's nothing a non-ops user can do here.
const watcher = reactive({
  globalEnabled: false,
  kbEnabled: false,
  intervalMs: 0,
  sourceType: null as string | null,
  busy: false,
})
async function loadWatcher(kbId: number) {
  try {
    const res: any = await wikiApi.getSourceWatcher(kbId)
    const d = res?.data ?? res
    watcher.globalEnabled = !!d.watcherEnabled
    watcher.kbEnabled = !!d.kbWatcherEnabled
    watcher.intervalMs = d.intervalMs || 0
    watcher.sourceType = d.sourceType || null
  } catch { /* leave defaults; auto-sync UI just shows disabled */ }
}
async function toggleWatcher(next: boolean) {
  if (!store.currentKB) return
  watcher.busy = true
  try {
    await wikiApi.setWatcherEnabled(store.currentKB.id, next)
    watcher.kbEnabled = next
    mcToast.success(t('common.saved'))
  } catch (e: any) {
    mcToast.error(e?.response?.data?.message || t('wiki.sources.toggleFailed'))
  } finally {
    watcher.busy = false
  }
}
watch(() => store.currentKB?.id, (id) => {
  if (id) {
    void loadWatcher(id as number)
    loadPathConfigs(id as number)
  }
}, { immediate: true })

// ─── Drag-over state ──────────────────────────────────────────────────────────
const { isDragging, onDragEnter, onDragLeave, onDrop: handleDrop } = useFileDrop(uploadDroppedFiles)

async function uploadDroppedFiles(event: DragEvent) {
  if (!event.dataTransfer?.files || !store.currentKB) return
  const kbId = store.currentKB.id
  await Promise.all(Array.from(event.dataTransfer.files).map(f => uploadFile(kbId, f)))
}

// ─── Optimistic upload items ──────────────────────────────────────────────────
interface UploadingFile {
  tempId: string
  name: string
  httpPct: number
  status: 'uploading' | 'error'
  errorMsg?: string
}

const uploadingFiles = ref<UploadingFile[]>([])
let tempIdCounter = 0

function addUploadingFile(name: string): UploadingFile {
  const item: UploadingFile = {
    tempId: `upload-${++tempIdCounter}`,
    name,
    httpPct: 0,
    status: 'uploading',
  }
  uploadingFiles.value.push(item)
  return item
}

function removeUploadingFile(tempId: string) {
  const idx = uploadingFiles.value.findIndex(f => f.tempId === tempId)
  if (idx >= 0) uploadingFiles.value.splice(idx, 1)
}

// ─── Upload helpers ───────────────────────────────────────────────────────────
async function uploadFile(kbId: number, file: File) {
  const item = addUploadingFile(file.name)
  try {
    await store.uploadRawFile(kbId, file, (pct) => {
      item.httpPct = pct
    })
    // Success: real item was added to store.rawMaterials, remove the optimistic placeholder
    removeUploadingFile(item.tempId)
  } catch (err: any) {
    item.status = 'error'
    item.errorMsg = err?.response?.data?.message || err?.message || t('wiki.uploadFailed', { name: file.name })
    mcToast.error(t('wiki.uploadFailed', { name: file.name }))
  }
}

function triggerFileInput() {
  fileInput.value?.click()
}

async function handleFileSelect(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files || !store.currentKB) return
  const kbId = store.currentKB.id
  // Upload all files concurrently
  await Promise.all(Array.from(input.files).map(f => uploadFile(kbId, f)))
  input.value = ''
}


async function handleAddText() {
  if (!store.currentKB) return
  await store.addRawText(store.currentKB.id, textTitle.value, textContent.value)
  showAddText.value = false
  textTitle.value = ''
  textContent.value = ''
}

async function reprocess(rawId: number) {
  if (!store.currentKB) return
  const kbId = store.currentKB.id
  await wikiApi.reprocessRaw(kbId, rawId)
  // Immediately mark local state as processing so SSE connects and progress bar shows
  const raw = store.rawMaterials.find(r => r.id === rawId)
  if (raw) {
    raw.processingStatus = 'processing'
    raw.progressDone = 0
    raw.progressTotal = 0
  }
  // Clear stale job entry
  delete rawJobs[rawId]
  await store.fetchRawMaterials(kbId)
  // Delayed re-fetch to catch final status if processing finishes before SSE connects
  setTimeout(() => { store.fetchRawMaterials(kbId) }, 5000)
  setTimeout(() => { store.fetchRawMaterials(kbId) }, 15000)
}

async function deleteRaw(rawId: number) {
  if (!store.currentKB) return
  await wikiApi.deleteRaw(store.currentKB.id, rawId)
  await store.fetchRawMaterials(store.currentKB.id)
}

async function cancelRaw(rawId: number) {
  if (!store.currentKB) return
  const kbId = store.currentKB.id
  // Optimistic flag — drives the spinner button and "正在取消…" badge text
  // so the click is visibly registered even if the pipeline is currently
  // mid-LLM call and won't reach its next abort checkpoint for several seconds.
  cancellingIds.value.add(rawId)
  try {
    await wikiApi.cancelRaw(kbId, rawId)
  } catch (e) {
    // Roll back the optimistic state if the call itself failed (auth /
    // network error). Without this the button would stay stuck in the
    // cancelling state forever.
    cancellingIds.value.delete(rawId)
    throw e
  }
  // Re-fetch periodically so the row's status flips from 'processing' to
  // 'cancelled' as soon as the pipeline observes the flag and writes its
  // terminal status — at which point the watch below clears the flag.
  await store.fetchRawMaterials(kbId)
  setTimeout(() => { store.fetchRawMaterials(kbId) }, 5000)
  setTimeout(() => { store.fetchRawMaterials(kbId) }, 15000)
}

async function downloadRaw(raw: { id: number; title?: string }) {
  if (!store.currentKB) return
  try {
    // The http interceptor returns the raw body for non-R-shaped responses,
    // so this resolves directly to the Blob (no .data unwrap needed).
    const blob = (await wikiApi.downloadRaw(store.currentKB.id, raw.id)) as unknown as Blob
    let filename = raw.title && raw.title.trim().length > 0 ? raw.title : `raw-${raw.id}`
    if (!filename.includes('.')) filename += '.txt'
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    // Revoke on next tick — some browsers cancel the in-flight download if we
    // revoke synchronously before the click handler returns.
    setTimeout(() => URL.revokeObjectURL(url), 0)
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e)
    mcToast.error(`${t('wiki.downloadFailed')}: ${msg}`)
  }
}

async function processAll() {
  if (!store.currentKB) return
  const kbId = store.currentKB.id
  await wikiApi.processKB(kbId)
  // Mark all pending materials as processing so SSE connects
  store.rawMaterials
    .filter(r => r.processingStatus === 'pending')
    .forEach(r => { r.processingStatus = 'processing'; r.progressDone = 0; r.progressTotal = 0 })
  await store.fetchRawMaterials(kbId)
  setTimeout(() => { store.fetchRawMaterials(kbId) }, 5000)
}

function toggleRawFilter(rawId: number) {
  if (!store.currentKB) return
  const kbId = store.currentKB.id
  if (store.selectedRawId === rawId) {
    store.clearRawFilter(kbId)
  } else {
    store.filterPagesByRaw(kbId, rawId)
  }
}
</script>

<style scoped>
.raw-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* Buttons */
.btn-primary { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; background: var(--mc-primary); color: white; border: none; border-radius: 10px; font-size: 14px; font-weight: 500; cursor: pointer; }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 10px; font-size: 14px; cursor: pointer; white-space: nowrap; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

/* Directory scan */
/* Auto-sync (per-KB source watcher) */
.auto-sync-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: -4px; }
.auto-sync-toggle { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--mc-text-secondary); cursor: pointer; }
.auto-sync-toggle.disabled { opacity: 0.55; cursor: not-allowed; }
.auto-sync-toggle input { cursor: inherit; }
.auto-sync-meta { font-size: 12px; color: var(--mc-text-tertiary); font-variant-numeric: tabular-nums; }
.auto-sync-hint { font-size: 12px; color: var(--mc-text-tertiary); }

/* Upload row: zone + add text side by side */
.upload-row { display: flex; gap: 12px; align-items: stretch; }
.upload-zone {
  flex: 1;
  border: 1px dashed var(--mc-border);
  border-radius: 16px;
  padding: 10px 16px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--mc-text-tertiary);
}
.upload-zone:hover { border-color: var(--mc-primary); background: var(--mc-primary-bg); }
.upload-zone.is-dragging {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
  box-shadow: 0 0 0 3px rgba(217,119,87,0.15);
  color: var(--mc-primary);
}
.upload-zone.is-uploading {
  border-color: var(--mc-primary);
  background: var(--mc-primary-bg);
  cursor: default;
  pointer-events: none;
}
.upload-zone svg { flex-shrink: 0; }
.upload-text { display: flex; flex-direction: column; gap: 2px; }
.upload-label { font-size: 14px; color: var(--mc-text-secondary); }
.upload-hint { font-size: 12px; color: var(--mc-text-tertiary); }
.add-text-btn { flex-shrink: 0; }

/* Spinner animation for uploading state */
.upload-spinner {
  flex-shrink: 0;
  animation: spin 1s linear infinite;
  color: var(--mc-primary);
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to   { transform: rotate(360deg); }
}

/* ── Phase 1: Filter Bar ───────────────────────────────────────────────── */
.filter-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 8px 10px;
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 14px;
}

.search-input-wrap {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 160px;
  flex: 1;
}
.search-icon {
  color: var(--mc-text-tertiary);
  flex-shrink: 0;
  display: flex;
}
.search-input {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 13px;
  color: var(--mc-text-primary);
  outline: none;
  font-family: inherit;
}
.search-input::placeholder { color: var(--mc-text-tertiary); }

.filter-sep { width: 1px; height: 24px; background: var(--mc-border-light); flex-shrink: 0; }

.filter-group { display: flex; align-items: center; gap: 6px; }
.filter-label { font-size: 11px; color: var(--mc-text-tertiary); white-space: nowrap; }

.select-filter {
  padding: 4px 24px 4px 8px;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  font-size: 12px;
  color: var(--mc-text-primary);
  background: var(--mc-bg-sunken);
  cursor: pointer;
  outline: none;
  appearance: auto;
  font-family: inherit;
}
.select-filter:focus { border-color: var(--mc-primary); }

.sort-dir-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  font-size: 13px;
  line-height: 1;
  color: var(--mc-text-secondary);
  background: var(--mc-bg-sunken);
  cursor: pointer;
  outline: none;
  flex-shrink: 0;
}
.sort-dir-btn:hover { border-color: var(--mc-primary); color: var(--mc-primary); }

.filter-chips { display: flex; gap: 6px; flex-wrap: wrap; }

.chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border: 1px solid var(--mc-border-light);
  border-radius: 9999px;
  font-size: 11px;
  color: var(--mc-text-secondary);
  background: transparent;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}
.chip:hover { border-color: var(--mc-primary); color: var(--mc-primary); }
.chip.active { background: var(--mc-primary-bg); border-color: var(--mc-primary); color: var(--mc-primary); font-weight: 500; }
.chip.green { color: var(--mc-success); border-color: rgba(90,138,90,0.3); }
.chip.green.active { background: var(--mc-success-bg, rgba(90,138,90,0.1)); border-color: var(--mc-success); color: var(--mc-success); }
.chip.blue { color: var(--mc-info); border-color: rgba(59,130,246,0.3); }
.chip.blue.active { background: var(--mc-info-bg, rgba(59,130,246,0.1)); border-color: var(--mc-info); color: var(--mc-info); }
.chip.yellow { color: var(--mc-warning, #d98e00); border-color: rgba(245,158,11,0.3); }
.chip.yellow.active { background: var(--mc-warning-bg, rgba(245,158,11,0.1)); border-color: var(--mc-warning, #d98e00); color: var(--mc-warning, #d98e00); }
.chip.red { color: var(--mc-danger); border-color: rgba(192,57,43,0.3); }
.chip.red.active { background: var(--mc-danger-bg); border-color: var(--mc-danger); color: var(--mc-danger); }

.results-count { font-size: 11px; color: var(--mc-text-tertiary); white-space: nowrap; margin-left: auto; }

/* ── Phase 3: Batch action bar & checkboxes ────────────────────────────── */
.batch-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  background: linear-gradient(135deg, var(--mc-primary-bg), rgba(217, 119, 87, 0.06));
  border: 1px solid rgba(217, 119, 87, 0.35);
  border-radius: 14px;
}
.batch-count { font-weight: 600; font-size: 13px; color: var(--mc-primary); white-space: nowrap; }
.batch-sep { width: 1px; height: 20px; background: rgba(217, 119, 87, 0.25); flex-shrink: 0; }
.batch-actions { display: flex; gap: 8px; flex-wrap: wrap; }
.batch-btn {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-secondary);
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s;
}
.batch-btn:hover { border-color: var(--mc-primary); color: var(--mc-primary); background: var(--mc-primary-bg); }
.batch-btn.danger:hover { border-color: var(--mc-danger); color: var(--mc-danger); background: var(--mc-danger-bg); }
.clear-sel { margin-left: auto; padding: 4px 8px; font-size: 12px; color: var(--mc-text-tertiary); border-radius: 4px; cursor: pointer; white-space: nowrap; transition: all 0.15s; }
.clear-sel:hover { color: var(--mc-text-primary); background: var(--mc-bg-sunken); }

.row-checkbox,
.group-checkbox {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
  border: 1.5px solid var(--mc-border);
  border-radius: 4px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}
.row-checkbox.checked,
.group-checkbox.checked { background: var(--mc-primary); border-color: var(--mc-primary); }
.row-checkbox svg,
.group-checkbox svg { display: none; }
.row-checkbox.checked svg,
.group-checkbox.checked svg { display: block; }
.group-checkbox.partial { border-color: var(--mc-primary); }
.group-checkbox.partial::after { content: ''; width: 8px; height: 2px; border-radius: 1px; background: var(--mc-primary); }

/* ── Phase 2: File groups ──────────────────────────────────────────────── */
.file-group {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border-light);
  border-radius: 14px;
  overflow: hidden;
  transition: box-shadow 0.15s;
}
.group-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  cursor: pointer;
  user-select: none;
  transition: background 0.12s;
}
.group-header:hover { background: var(--mc-bg-sunken); }
.group-chevron {
  flex-shrink: 0;
  width: 12px;
  text-align: center;
  font-size: 10px;
  color: var(--mc-text-tertiary);
  transition: transform 0.2s;
}
.group-chevron.open { transform: rotate(90deg); }
.group-alias { flex: 1; min-width: 0; font-weight: 600; font-size: 13px; color: var(--mc-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.group-stats { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
.stat-pill { padding: 2px 7px; border-radius: 10px; font-size: 10px; font-weight: 600; font-variant-numeric: tabular-nums; }
.stat-pill.total { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.stat-pill.ok { background: rgba(90, 138, 90, 0.15); color: var(--mc-success); }
.stat-pill.processing { background: var(--mc-primary-bg); color: var(--mc-primary); }
.stat-pill.pending { background: rgba(245, 158, 11, 0.12); color: var(--mc-warning, #d98e00); }
.stat-pill.err { background: var(--mc-danger-bg); color: var(--mc-danger); }
.group-body { display: flex; flex-direction: column; gap: 8px; padding: 8px 12px 10px; }
.group-footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; padding: 4px 2px 0; font-size: 11px; color: var(--mc-text-tertiary); }
.group-footer-time { font-variant-numeric: tabular-nums; }
.group-actions { display: flex; gap: 4px; flex-shrink: 0; }
.group-cfg-btn { width: 26px; height: 26px; }
.group-schedule {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 9999px;
  font-size: 10px;
  font-weight: 600;
  color: var(--mc-success);
  background: rgba(90, 138, 90, 0.12);
}
.group-schedule svg { flex-shrink: 0; }

/* ── Phase 4: Path config modal ────────────────────────────────────────── */
.add-path-card {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 14px;
  border: 1.5px dashed var(--mc-border);
  border-radius: 14px;
  background: transparent;
  color: var(--mc-text-tertiary);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}
.add-path-card:hover { border-color: var(--mc-primary); color: var(--mc-primary); background: var(--mc-primary-bg); }

.path-modal { max-width: 560px; }
.frontend-only-hint {
  margin: 0 0 16px;
  padding: 8px 10px;
  font-size: 12px;
  color: var(--mc-warning, #d98e00);
  background: rgba(245, 158, 11, 0.1);
  border-radius: 8px;
}
.form-hint { display: block; margin-top: 4px; font-size: 11px; color: var(--mc-text-tertiary); }
.schedule-config-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  border-radius: 8px;
}
.toggle-switch { position: relative; display: inline-block; width: 32px; height: 18px; flex-shrink: 0; cursor: pointer; }
.toggle-switch input { display: none; }
.toggle-track { position: absolute; inset: 0; background: var(--mc-border); border-radius: 9px; transition: background 0.2s; }
.toggle-switch input:checked + .toggle-track { background: var(--mc-primary); }
.toggle-thumb { position: absolute; top: 2px; left: 2px; width: 14px; height: 14px; background: #fff; border-radius: 50%; box-shadow: 0 1px 3px rgba(0,0,0,0.15); transition: transform 0.2s; }
.toggle-switch input:checked ~ .toggle-thumb { transform: translateX(14px); }
.schedule-state { font-size: 12px; color: var(--mc-text-tertiary); }
.schedule-state.on { color: var(--mc-success); font-weight: 600; }
.cron-select { width: 100%; margin-top: 8px; }
.cron-custom-input { margin-top: 8px; font-family: var(--mc-font-mono, monospace); }
.mode-selector { display: flex; gap: 10px; }
.mode-option {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  padding: 10px 14px;
  border: 1.5px solid var(--mc-border-light);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
}
.mode-option:hover { background: var(--mc-bg-sunken); }
.mode-option.selected { border-color: var(--mc-primary); background: var(--mc-primary-bg); }
.mode-option input[type="radio"] { accent-color: var(--mc-primary); }
.mode-option-text { display: flex; flex-direction: column; gap: 2px; }
.mode-option-title { font-size: 12px; font-weight: 600; color: var(--mc-text-primary); }
.mode-option-hint { font-size: 11px; color: var(--mc-text-tertiary); }
.path-modal-actions { justify-content: space-between; }
.path-modal-actions-right { display: flex; gap: 10px; margin-left: auto; }
.btn-remove-path {
  padding: 8px 14px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  color: var(--mc-danger);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}
.btn-remove-path:hover { background: var(--mc-danger-bg); border-color: var(--mc-danger); }

/* Phase 5: file preview modal */
.preview-modal { width: 720px; max-width: 92vw; }
.preview-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.preview-title { display: inline-flex; align-items: center; gap: 8px; font-size: 15px; font-weight: 600; color: var(--mc-text-primary); min-width: 0; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.preview-title svg { flex-shrink: 0; color: var(--mc-text-tertiary); }
.preview-header-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.preview-dl-btn { display: inline-flex; align-items: center; gap: 5px; padding: 6px 12px; font-size: 12px; }
.preview-meta { display: flex; flex-wrap: wrap; gap: 8px 16px; align-items: center; margin-bottom: 14px; }
.preview-meta-item { font-size: 12px; color: var(--mc-text-tertiary); }
.preview-body { margin-bottom: 16px; }
.preview-content { margin: 0; background: var(--mc-bg-muted); border: 1px solid var(--mc-border-light); border-radius: 8px; padding: 16px; font-family: var(--mc-font-mono, monospace); font-size: 12px; color: var(--mc-text-secondary); max-height: 400px; overflow-y: auto; white-space: pre-wrap; word-break: break-word; line-height: 1.7; }
.preview-placeholder { background: var(--mc-bg-muted); border: 1px solid var(--mc-border-light); border-radius: 8px; padding: 32px 16px; text-align: center; font-size: 13px; color: var(--mc-text-tertiary); }

/* Phase 6: error file partition */
.error-section { background: var(--mc-danger-bg); border: 1px solid var(--mc-danger); border-radius: 14px; overflow: hidden; }
.error-section-header { display: flex; align-items: center; gap: 10px; padding: 12px 14px; cursor: pointer; user-select: none; }
.error-icon { color: var(--mc-danger); display: inline-flex; flex-shrink: 0; }
.error-title { font-weight: 600; color: var(--mc-danger); font-size: 13px; }
.error-count { padding: 1px 8px; border-radius: 9999px; background: var(--mc-danger); color: #fff; font-size: 11px; font-weight: 700; }
.error-hint-text { margin-left: 4px; font-size: 11px; color: var(--mc-text-tertiary); }
.error-actions-bar { display: flex; gap: 8px; flex-shrink: 0; margin-left: auto; }
.error-bar-btn { padding: 4px 10px; border: 1px solid var(--mc-danger); border-radius: 8px; background: transparent; color: var(--mc-danger); font-size: 11px; cursor: pointer; transition: all 0.15s; }
.error-bar-btn:hover { background: var(--mc-danger); color: #fff; }
.error-bar-btn.danger { font-weight: 600; }
.error-body { border-top: 1px solid var(--mc-danger); }
.error-item { display: flex; align-items: flex-start; gap: 12px; padding: 10px 14px; border-bottom: 1px solid var(--mc-border-light); }
.error-item:last-child { border-bottom: none; }
.error-file-emoji { font-size: 16px; flex-shrink: 0; line-height: 1.4; }
.error-file-info { flex: 1; min-width: 0; }
.error-file-name { font-size: 12px; color: var(--mc-text-primary); display: flex; align-items: center; gap: 6px; }
.error-file-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.error-file-title.clickable { cursor: pointer; }
.error-file-title.clickable:hover { color: var(--mc-primary); text-decoration: underline; text-underline-offset: 2px; }
.error-tag { display: inline-block; padding: 1px 6px; border-radius: 4px; font-size: 10px; font-weight: 600; background: var(--mc-danger); color: #fff; flex-shrink: 0; }
.error-msg { font-size: 11px; color: var(--mc-text-tertiary); font-family: var(--mc-font-mono, monospace); margin-top: 3px; word-break: break-word; }
.error-actions { display: flex; gap: 6px; flex-shrink: 0; align-items: center; }
.error-item-btn { padding: 3px 8px; border: 1px solid var(--mc-border); border-radius: 7px; background: var(--mc-bg-elevated); color: var(--mc-text-secondary); font-size: 11px; cursor: pointer; transition: all 0.15s; }
.error-item-btn:hover { border-color: var(--mc-primary); color: var(--mc-primary); }

/* Raw list */
.raw-list { display: flex; flex-direction: column; gap: 12px; padding-top: 4px; }
.raw-list-title { font-size: 12px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; color: var(--mc-text-tertiary); margin-bottom: 4px; }
.empty-hint { text-align: center; padding: 24px 0; font-size: 14px; color: var(--mc-text-tertiary); }

.raw-item { display: flex; flex-direction: column; gap: 8px; padding: 12px 14px; background: linear-gradient(180deg, var(--mc-bg-elevated), var(--mc-bg-muted)); border: 1px solid var(--mc-border-light); border-radius: 14px; font-size: 13px; transition: border-color 0.15s, transform 0.15s; cursor: pointer; }
.raw-item:hover { border-color: var(--mc-border); transform: translateY(-1px); }
.raw-item--active { border-color: var(--mc-primary) !important; background: var(--mc-primary-bg) !important; transform: translateY(-1px); }
.raw-item--uploading { cursor: default; opacity: 0.85; }
.raw-item--uploading:hover { transform: none; border-color: var(--mc-border-light); }
.raw-item-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }

.raw-item-info { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; }
.raw-item-title { font-weight: 500; color: var(--mc-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.raw-item-title.clickable { cursor: pointer; }
.raw-item-title.clickable:hover { color: var(--mc-primary); text-decoration: underline; text-underline-offset: 2px; }
.raw-item-type { font-size: 10px; padding: 2px 6px; background: var(--mc-bg-sunken); border-radius: 4px; text-transform: uppercase; color: var(--mc-text-tertiary); letter-spacing: 0.02em; }
.raw-item-meta { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.raw-item-actions { display: flex; gap: 4px; flex-shrink: 0; }
.error-hint { font-size: 11px; color: var(--mc-danger); max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.warning-hint { font-size: 11px; color: var(--mc-warning, #d98e00); max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.page-count-chip { display: inline-flex; align-items: center; gap: 3px; font-size: 11px; font-weight: 500; color: var(--mc-text-secondary); background: var(--mc-bg-sunken); border-radius: 9999px; padding: 2px 7px; }

/* Two-phase digest progress bar (RFC-012 M2 v2 UI) */
.raw-progress { display: flex; align-items: center; gap: 10px; padding-top: 2px; }
.raw-progress-track { flex: 1; height: 4px; background: var(--mc-bg-sunken); border-radius: 9999px; overflow: hidden; position: relative; }
.raw-progress-fill { height: 100%; background: var(--mc-primary); border-radius: 9999px; transition: width 0.3s ease; }
.raw-progress-fill.indeterminate {
  width: 30%;
  position: absolute;
  left: 0;
  animation: raw-progress-slide 1.6s ease-in-out infinite;
}
@keyframes raw-progress-slide {
  0%   { transform: translateX(-100%); }
  50%  { transform: translateX(170%); }
  100% { transform: translateX(330%); }
}
.raw-progress-label { font-size: 11px; color: var(--mc-text-tertiary); font-variant-numeric: tabular-nums; flex-shrink: 0; min-width: 56px; text-align: right; }

/* Icon button */
.btn-icon { width: 30px; height: 30px; border: 1px solid var(--mc-border-light); background: var(--mc-bg-elevated); cursor: pointer; border-radius: 8px; color: var(--mc-text-secondary); transition: all 0.15s; display: flex; align-items: center; justify-content: center; }
.btn-icon:hover { background: var(--mc-bg-sunken); color: var(--mc-primary); border-color: var(--mc-border); }
.btn-icon-danger:hover { background: var(--mc-danger-bg); color: var(--mc-danger); border-color: var(--mc-danger); }
.btn-icon-resume { color: var(--mc-primary); border-color: var(--mc-primary); background: var(--mc-primary-bg); }
.btn-icon-resume:hover { background: var(--mc-primary); color: #fff; border-color: var(--mc-primary); }

/* Status badges */
.status-badge { font-size: 10px; padding: 2px 8px; border-radius: 9999px; text-transform: uppercase; font-weight: 500; letter-spacing: 0.02em; }
.status-badge.pending { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.status-badge.uploading { background: rgba(59, 130, 246, 0.12); color: #3b82f6; }
.status-badge.processing { background: var(--mc-primary-bg); color: var(--mc-primary); }
.status-badge.completed { background: rgba(90, 138, 90, 0.15); color: var(--mc-success); }
.status-badge.partial { background: rgba(217, 119, 87, 0.15); color: var(--mc-primary); }
.status-badge.failed { background: var(--mc-danger-bg); color: var(--mc-danger); }
.status-badge.cancelled { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.status-badge.cancelling { background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }

.btn-icon.btn-icon-cancelling { cursor: default; opacity: 0.7; }
.btn-icon.btn-icon-cancelling .spinner { animation: rmp-spin 0.9s linear infinite; }
@keyframes rmp-spin { to { transform: rotate(360deg); } }

/* Process button */
.process-btn { width: 100%; justify-content: center; margin-top: 16px; }

/* Modal */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal-content { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; width: 100%; max-width: 640px; padding: 24px; max-height: 80vh; overflow-y: auto; box-shadow: 0 20px 60px rgba(0,0,0,0.15); }
.modal-title { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0 0 16px; }

/* Form */
.form-group { margin-bottom: 16px; }
.form-group label { display: block; font-size: 13px; font-weight: 500; margin-bottom: 6px; color: var(--mc-text-secondary); }
.form-input { width: 100%; padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; background: var(--mc-bg-sunken); color: var(--mc-text-primary); outline: none; font-family: inherit; }
.form-input:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }

.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 16px; }

@media (max-width: 768px) {
  .filter-bar { flex-direction: column; align-items: stretch; }
  .search-input-wrap { min-width: 0; }
  .filter-sep { display: none; }

  .upload-row {
    flex-direction: column;
  }

  .add-text-btn,
  .process-btn {
    width: 100%;
    justify-content: center;
  }

  .raw-item {
    align-items: flex-start;
    flex-direction: column;
  }

  .raw-item-meta,
  .raw-item-actions {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
