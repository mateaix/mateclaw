<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner skills-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">Capabilities</div>
            <h1 class="mc-page-title">{{ t('skills.title') }}</h1>
            <p class="mc-page-desc">{{ t('skills.desc') }}</p>
          </div>
          <div class="header-actions">
            <button class="btn-secondary" @click="handleRefreshRuntime" :disabled="refreshing">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/>
                <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>
              </svg>
              {{ refreshing ? t('skills.refreshing') : t('skills.refreshRuntime') }}
            </button>
            <button class="btn-secondary" @click="$router.push('/skills/templates')">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/>
                <rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/>
              </svg>
              {{ t('skills.browseTemplates') }}
            </button>
            <button class="btn-secondary" @click="showImportDialog = true">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>
              </svg>
              {{ t('skills.importSkill') }}
            </button>
            <button class="btn-primary" @click="openCreateModal">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
              </svg>
              {{ t('skills.newSkill') }}
            </button>
          </div>
        </div>

        <!-- 分类 Tab -->
        <div class="category-tabs mc-surface-card">
          <button v-for="tab in categoryTabs" :key="tab.value" class="cat-tab"
            :class="{ active: query.skillType === tab.value }" @click="onTabChange(tab.value)">
            <span class="cat-icon">{{ tab.icon }}</span>
            {{ tab.label }}
            <span class="cat-count">{{ getCategoryCount(tab.value) }}</span>
          </button>
        </div>

        <!-- Search + status filter (RFC-042 §2.1) -->
        <div class="skill-filter-bar mc-surface-card">
          <input
            v-model="query.keyword"
            class="skill-search-input"
            type="search"
            :placeholder="t('skills.search.placeholder')"
          />
          <select v-model="query.statusFilter" class="skill-status-filter" @change="onFilterChange">
            <option value="">{{ t('skills.filter.all') }}</option>
            <option value="enabled">{{ t('skills.filter.enabled') }}</option>
            <option value="disabled">{{ t('skills.filter.disabled') }}</option>
            <option value="scan_failed">{{ t('skills.filter.scanFailed') }}</option>
          </select>
          <select v-model="query.sort" class="skill-status-filter" @change="onFilterChange">
            <option value="recommended">{{ t('skills.sort.recommended') }}</option>
            <option value="name">{{ t('skills.sort.name') }}</option>
            <option value="status">{{ t('skills.sort.status') }}</option>
            <option value="type">{{ t('skills.sort.source') }}</option>
            <option value="updated">{{ t('skills.sort.updated') }}</option>
          </select>
        </div>

        <!-- Skill grid — RFC-090 §4.2 (Phase 1 slim).
             Card surfaces 5 things: icon · name · status · description · actions.
             All findings, deps, paths, lessons, used-by are in the detail drawer. -->
        <div class="skill-grid" v-if="skills.length > 0">
          <div
            v-for="skill in skills"
            :key="skill.id"
            class="skill-card mc-surface-card"
            :class="{ disabled: !skill.enabled }"
            role="button"
            tabindex="0"
            @click="openDetailDrawer(skill)"
            @keydown.enter="openDetailDrawer(skill)"
          >
            <div class="skill-header">
              <div class="skill-icon-wrap" :class="getSkillIconBg(skill.skillType)">
                <SkillIcon
                  :value="skill.icon"
                  :fallback="getSkillIcon(skill.skillType)"
                  :size="22"
                />
              </div>
              <div class="skill-meta">
                <h3 class="skill-name">{{ resolveSkillName(skill) }}</h3>
                <!-- RFC-042 §2.2.4 — slug under display name when they differ -->
                <div v-if="hasI18nName(skill)" class="skill-slug">{{ skill.name }}</div>
              </div>
              <!-- Issue #83: virtual MCP/ACP skills are view-only mirrors of the
                   underlying MCP/ACP server row, with no mate_skill row to flip.
                   Hiding the toggle here matches how the configure / delete
                   buttons are gated below; users enable/disable from the
                   Settings ▸ MCP connection page instead. -->
              <label v-if="!isSkillRowVirtual(skill)" class="toggle-switch" @click.stop>
                <input type="checkbox" :checked="skill.enabled" @change="toggleSkill(skill)" />
                <span class="toggle-slider"></span>
              </label>
              <span
                v-else
                class="virtual-toggle-hint"
                :title="$t('skills.virtualReadonlyHint')"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/>
                  <path d="M7 11V7a5 5 0 0 1 10 0v4"/>
                </svg>
              </span>
            </div>

            <p class="skill-desc">{{ skill.description || t('skills.noDescription') }}</p>

            <!-- Single status row: status pill (folds runtime/sec/deps/features) + source + version -->
            <div class="skill-status-row">
              <span class="status-pill" :class="getStatusPill(skill).cls">
                {{ getStatusPill(skill).label }}
              </span>
              <span class="source-label" :class="getSourceClass(skill)">{{ getSourceLabel(skill) }}</span>
              <span v-if="skill.version" class="skill-version">v{{ skill.version }}</span>
            </div>

            <div class="skill-footer" @click.stop>
              <span v-if="skill.author" class="skill-author">by {{ skill.author }}</span>
              <div class="skill-actions">
                <button
                  v-if="needsSetup(skill)"
                  class="skill-btn skill-btn-setup"
                  :title="t('skills.actions.setUp')"
                  @click="openPreflight(skill)"
                >
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.09a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9c0 .66.26 1.3.73 1.77.47.47 1.11.73 1.77.73H21a2 2 0 1 1 0 4h-.09c-.66 0-1.3.26-1.77.73-.47.47-.73 1.11-.73 1.77z"/>
                  </svg>
                </button>
                <button
                  v-if="!isSkillRowVirtual(skill)"
                  class="skill-btn"
                  :title="t('skills.actions.configure')"
                  @click="openEditFromCard(skill)"
                >
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                    <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                  </svg>
                </button>
                <button
                  v-if="skill.skillType !== 'builtin' && !isSkillRowVirtual(skill)"
                  class="skill-btn danger"
                  :title="t('skills.actions.delete')"
                  @click="deleteSkill(skill)"
                >
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="3 6 5 6 21 6"/>
                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
                  </svg>
                </button>
              </div>
            </div>
          </div>
        </div>

        <div v-else class="empty-state mc-surface-card">
          <div class="empty-icon">🛠️</div>
          <h3>{{ t('skills.empty') }}</h3>
          <p>{{ t('skills.emptyDesc') }}</p>
        </div>

        <!-- Pagination (RFC-042 §2.1) — MateClaw frosted-pill component. -->
        <div class="skill-pagination">
          <McPagination
            v-model:page="query.page"
            v-model:size="query.size"
            :total="total"
            :sizes="[20, 50]"
            @change="onPagerChange"
          />
        </div>
      </div>
    </div>

    <!-- Import Hub Dialog -->
    <ImportHubDialog v-model:visible="showImportDialog" @installed="onSkillInstalled" />

    <!-- RFC-090 §4.4 Pre-flight install dialog -->
    <PreflightInstallDialog
      v-model:visible="preflightVisible"
      :skill-id="preflightSkillId"
      :skill-name="preflightSkillName"
    />

    <!-- Skill detail drawer.
         Mirrors Settings/Models/AddProviderDrawer.vue — Teleport + frosted
         glass, iOS-spring slide, mobile bottom-sheet, dark variants. The
         old el-drawer was off-brand and didn't match the rest of the app. -->
    <Teleport to="body">
      <Transition name="mc-drawer-fade">
        <div
          v-if="detailDrawerVisible && detailSkill"
          class="mc-drawer-overlay"
          :class="{ 'mc-drawer-overlay--wide': editingBody }"
          @click.self="closeDetailDrawer"
        >
          <div class="mc-drawer-panel" :class="{ 'mc-drawer-panel--wide': editingBody }">
            <div class="mc-drawer-header">
              <div class="mc-drawer-header__meta">
                <span class="mc-drawer-icon-shell">
                  <SkillIcon :value="detailSkill.icon" :size="24" />
                </span>
                <div>
                  <h3 class="mc-drawer-title">{{ resolveSkillName(detailSkill) }}</h3>
                  <p class="mc-drawer-subtitle">
                    {{ editingBody ? t('skills.detail.editingSource') : (detailSkill.description || detailSkill.name) }}
                  </p>
                </div>
              </div>
              <button
                class="mc-drawer-close"
                :title="t('common.cancel')"
                @click="closeDetailDrawer"
              >
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4">
                  <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </button>
            </div>

            <!-- Body-edit takeover: the entire drawer becomes one editor.
                 Tabs and other blocks are hidden so the user can think in
                 prose, not chrome. Save triggers a runtime refresh. -->
            <div v-if="editingBody" class="mc-drawer-content mc-drawer-content--takeover">
              <div class="takeover-toolbar">
                <span class="takeover-section">SKILL.md</span>
                <div class="edit-actions">
                  <button class="detail-edit-btn detail-edit-cancel" @click="cancelEditBody" :disabled="savingEdit">
                    {{ t('skills.actions.cancel') }}
                  </button>
                  <button class="detail-edit-btn detail-edit-save" @click="saveBody" :disabled="savingEdit">
                    {{ savingEdit ? t('common.loading') : t('skills.actions.save') }}
                  </button>
                </div>
              </div>
              <textarea
                v-model="editBodyForm.skillContent"
                class="takeover-editor"
                :placeholder="`# Skill Guide\n\n## When to use\n...`"
                spellcheck="false"
              ></textarea>
              <template v-if="detailSkill.skillType === 'dynamic'">
                <div class="takeover-toolbar takeover-toolbar--sub">
                  <span class="takeover-section">{{ t('skills.fields.sourceCode') }}</span>
                </div>
                <textarea
                  v-model="editBodyForm.sourceCode"
                  class="takeover-editor takeover-editor--secondary"
                  :placeholder="t('skills.placeholders.sourceCode')"
                  spellcheck="false"
                ></textarea>
              </template>
            </div>

            <div v-else class="mc-drawer-content">
              <div class="detail-tabs">
          <button class="detail-tab" :class="{ active: detailTab === 'overview' }" @click="detailTab = 'overview'">
            {{ t('skills.detail.overview') }}
          </button>
          <button class="detail-tab" :class="{ active: detailTab === 'body' }" @click="detailTab = 'body'">
            {{ t('skills.detail.body') }}
          </button>
          <button class="detail-tab" :class="{ active: detailTab === 'tools' }" @click="detailTab = 'tools'">
            {{ t('skills.detail.tools') }}
            <span v-if="detailToolsCount > 0" class="tab-count">{{ detailToolsCount }}</span>
          </button>
          <button class="detail-tab" :class="{ active: detailTab === 'features' }" @click="detailTab = 'features'">
            {{ t('skills.detail.features') }}
            <span v-if="detailFeaturesCount > 0" class="tab-count">{{ detailFeaturesCount }}</span>
          </button>
          <button class="detail-tab" :class="{ active: detailTab === 'security' }" @click="detailTab = 'security'">
            {{ t('skills.detail.security') }}
          </button>
          <button class="detail-tab" :class="{ active: detailTab === 'lessons' }" @click="detailTab = 'lessons'">
            {{ t('skills.detail.lessons') }}
          </button>
          <button class="detail-tab" :class="{ active: detailTab === 'secrets' }" @click="detailTab = 'secrets'">
            {{ t('skills.detail.secrets') }}
          </button>
          <button class="detail-tab" :class="{ active: detailTab === 'memory' }" @click="detailTab = 'memory'">
            {{ t('skills.detail.memory') }}
            <span v-if="detailEmployees.length > 0" class="tab-count">{{ detailEmployees.length }}</span>
          </button>
        </div>

        <!-- Overview tab — manifest-projected chips (read-only) +
             DB-only display overrides (editable) + collapsed manifest. -->
        <div v-if="detailTab === 'overview'" class="detail-section">
          <!-- Manifest-projected fields (chips, read-only).
               These columns are overwritten by SkillPackageResolver from
               manifest_json on every resolve, so editing them on the row
               wouldn't stick. Surface them as facts, not knobs. -->
          <div class="detail-block">
            <div class="detail-block-head">
              <h4 class="detail-block-title">{{ t('skills.detail.manifestProjectedSection') }}</h4>
            </div>
            <p class="detail-hint">{{ t('skills.detail.manifestProjectedHint') }}</p>
            <div class="meta-chips">
              <span class="meta-chip"><span class="meta-chip-label">slug</span><code>{{ detailSkill.name }}</code></span>
              <span class="meta-chip"><span class="meta-chip-label">{{ t('skills.fields.type') }}</span>{{ detailSkill.skillType || '—' }}</span>
              <span v-if="detailSkill.version" class="meta-chip"><span class="meta-chip-label">{{ t('skills.fields.version') }}</span>v{{ detailSkill.version }}</span>
              <span v-if="detailSkill.author" class="meta-chip"><span class="meta-chip-label">{{ t('skills.fields.author') }}</span>{{ detailSkill.author }}</span>
            </div>
          </div>

          <!-- DB-only display overrides (editable) -->
          <div class="detail-block">
            <div class="detail-block-head">
              <h4 class="detail-block-title">{{ t('skills.detail.displayOverridesSection') }}</h4>
              <button
                v-if="!isVirtualSkill && !editingIdentity"
                class="detail-edit-btn"
                @click="startEditIdentity"
              >
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                  <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                </svg>
                {{ t('skills.actions.edit') }}
              </button>
              <div v-else-if="editingIdentity" class="edit-actions">
                <button class="detail-edit-btn detail-edit-cancel" @click="cancelEditIdentity" :disabled="savingEdit">
                  {{ t('skills.actions.cancel') }}
                </button>
                <button class="detail-edit-btn detail-edit-save" @click="saveIdentity" :disabled="savingEdit">
                  {{ savingEdit ? t('common.loading') : t('skills.actions.save') }}
                </button>
              </div>
            </div>
            <p v-if="isVirtualSkill" class="detail-readonly-banner">{{ t('skills.detail.virtualReadonly') }}</p>
            <p v-else class="detail-hint">{{ t('skills.detail.displayOverridesHint') }}</p>

            <!-- Icon row spans both modes — view shows the resolved
                 glyph + label; edit turns the tile into a "pick"
                 affordance that opens the picker. -->
            <div class="identity-icon-row">
              <SkillIcon :value="editingIdentity ? editForm.icon : detailSkill.icon" :size="40" class="identity-icon-preview" />
              <div class="identity-icon-meta">
                <span class="identity-icon-label">{{ t('skills.fields.icon') }}</span>
                <code v-if="(editingIdentity ? editForm.icon : detailSkill.icon)" class="identity-icon-value">
                  {{ editingIdentity ? editForm.icon : detailSkill.icon }}
                </code>
                <span v-else class="identity-icon-empty">{{ t('common.iconPicker.none') }}</span>
              </div>
              <button
                v-if="editingIdentity"
                type="button"
                class="detail-edit-btn"
                @click="openIconPickerFor('edit')"
              >
                {{ t('common.iconPicker.pickerOpen') }}
              </button>
            </div>

            <dl v-if="!editingIdentity" class="identity-grid">
              <div class="kv"><dt>{{ t('skills.fields.nameZh') }}</dt><dd>{{ detailSkill.nameZh || '—' }}</dd></div>
              <div class="kv"><dt>{{ t('skills.fields.nameEn') }}</dt><dd>{{ detailSkill.nameEn || '—' }}</dd></div>
              <div class="kv kv-full"><dt>{{ t('skills.fields.tags') }}</dt><dd>{{ detailSkill.tags || '—' }}</dd></div>
              <div class="kv kv-full"><dt>{{ t('skills.fields.description') }}</dt><dd>{{ detailSkill.description || '—' }}</dd></div>
            </dl>
            <div v-else class="form-grid form-grid-tight">
              <div class="form-group">
                <label class="form-label">{{ t('skills.fields.nameZh') }}</label>
                <input v-model="editForm.nameZh" class="form-input" :placeholder="t('skills.placeholders.nameZh')" />
              </div>
              <div class="form-group">
                <label class="form-label">{{ t('skills.fields.nameEn') }}</label>
                <input v-model="editForm.nameEn" class="form-input" :placeholder="t('skills.placeholders.nameEn')" />
              </div>
              <div class="form-group full-width">
                <label class="form-label">{{ t('skills.fields.tags') }}</label>
                <input v-model="editForm.tags" class="form-input" :placeholder="t('skills.placeholders.tags')" />
              </div>
              <div class="form-group full-width">
                <label class="form-label">{{ t('skills.fields.description') }}</label>
                <input v-model="editForm.description" class="form-input" :placeholder="t('skills.placeholders.description')" />
              </div>
            </div>
            <p v-if="editingIdentity && manifestDeclaresIcon" class="detail-readonly-banner">
              {{ t('common.iconPicker.reprojectionWarning') }}
            </p>
          </div>

          <details class="detail-collapsible">
            <summary>{{ t('skills.detail.viewRawManifest') }}</summary>
            <p v-if="!detailManifest" class="detail-empty">{{ t('skills.detail.noManifest') }}</p>
            <pre v-else class="detail-pre">{{ detailManifestPretty }}</pre>
          </details>
        </div>

        <!-- Body tab — SKILL.md (+ dynamic sourceCode). Read mode shows
             a preview; edit lives in the full-drawer takeover further
             down (rendered outside the tab grid when editingBody=true). -->
        <div v-if="detailTab === 'body'" class="detail-section">
          <div class="detail-block">
            <div class="detail-block-head">
              <h4 class="detail-block-title">{{ t('skills.fields.skillContent') }}</h4>
              <button
                v-if="!isVirtualSkill"
                class="detail-edit-btn detail-edit-primary"
                @click="startEditBody"
              >
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                  <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                </svg>
                {{ t('skills.detail.editSource') }}
              </button>
            </div>
            <p class="detail-hint">{{ t('skills.detail.bodyHint') }}</p>
            <p v-if="!detailSkill.skillContent" class="detail-empty">{{ t('skills.detail.noBody') }}</p>
            <pre v-else class="detail-pre">{{ detailSkill.skillContent }}</pre>

            <template v-if="detailSkill.skillType === 'dynamic'">
              <div class="detail-block-head detail-subhead">
                <h4 class="detail-block-title">{{ t('skills.fields.sourceCode') }}</h4>
              </div>
              <p class="detail-hint">{{ t('skills.detail.sourceCodeHint') }}</p>
              <pre v-if="detailSkill.sourceCode" class="detail-pre">{{ detailSkill.sourceCode }}</pre>
              <p v-else class="detail-empty">—</p>
            </template>
          </div>
        </div>
        <!-- Tools tab -->
        <div v-if="detailTab === 'tools'" class="detail-section">
          <p v-if="detailToolsCount === 0" class="detail-empty">{{ t('skills.detail.noTools') }}</p>
          <ul v-else class="detail-tool-list">
            <li v-for="tool in detailEffectiveTools" :key="tool" class="detail-tool-item">
              <code>{{ tool }}</code>
            </li>
          </ul>
          <p class="detail-hint">{{ t('skills.detail.toolsHint') }}</p>
        </div>
        <!-- Features tab -->
        <div v-if="detailTab === 'features'" class="detail-section">
          <p v-if="detailFeaturesCount === 0" class="detail-empty">{{ t('skills.detail.noFeatures') }}</p>
          <ul v-else class="detail-feature-list">
            <li v-for="feat in detailFeatures" :key="feat.id" class="detail-feature-item">
              <div class="detail-feature-head">
                <span class="detail-feature-id">{{ feat.id }}</span>
                <span class="detail-feature-status" :class="`feat-${(feat.status || 'unknown').toLowerCase()}`">
                  {{ feat.status }}
                </span>
              </div>
              <div v-if="feat.label" class="detail-feature-label">{{ feat.label }}</div>
              <div v-if="feat.requires?.length" class="detail-feature-meta">
                <span class="detail-meta-key">requires:</span>
                <span v-for="r in feat.requires" :key="r" class="detail-feature-tag">{{ r }}</span>
              </div>
              <div v-if="feat.platforms?.length" class="detail-feature-meta">
                <span class="detail-meta-key">platforms:</span>
                <span v-for="p in feat.platforms" :key="p" class="detail-feature-tag">{{ p }}</span>
              </div>
              <div v-if="feat.fallbackMessage" class="detail-feature-fallback">
                {{ feat.fallbackMessage }}
              </div>
            </li>
          </ul>
        </div>
        <!-- Security tab — consolidates scan status, findings, missing deps,
             runtime error, and resolved skill path. Lifted off the card. -->
        <div v-if="detailTab === 'security'" class="detail-section">
          <div class="detail-security-row">
            <span class="detail-meta-key">{{ t('skills.detail.scanStatus') }}:</span>
            <span class="status-pill" :class="getScanPillCls(detailSkill)">
              {{ getScanPillLabel(detailSkill) }}
            </span>
            <span v-if="detailSkill.securityScanTime" class="scan-findings-time">
              · {{ formatScanTime(detailSkill.securityScanTime) }}
            </span>
            <!-- Issue #83 follow-up: virtual MCP/ACP skills can't be rescanned —
                 backend rejects with err.skill.virtual_readonly. Hide the button
                 so users don't trigger a guaranteed 4xx; the readonly banner
                 already tells them where to go. -->
            <button
              v-if="!isVirtualSkill"
              class="scan-rescan-btn"
              :disabled="rescanning[String(detailSkill.id)]"
              @click="rescanSkill(detailSkill)"
            >
              {{ rescanning[String(detailSkill.id)] ? t('skills.security.rescanning') : t('skills.security.rescan') }}
            </button>
          </div>

          <ul v-if="parsedFindings(detailSkill).length > 0" class="scan-findings-list">
            <li
              v-for="(f, idx) in parsedFindings(detailSkill)"
              :key="`${detailSkill.id}-f-${idx}`"
              class="scan-finding-item"
              :class="`sev-${(f.severity || 'info').toLowerCase()}`"
            >
              <div class="scan-finding-head">
                <span class="scan-finding-sev">[{{ f.severity || 'INFO' }}]</span>
                <span class="scan-finding-id">{{ f.ruleId || f.category || '—' }}</span>
                <span v-if="f.filePath" class="scan-finding-loc">
                  {{ f.filePath }}<span v-if="f.lineNumber">:{{ f.lineNumber }}</span>
                </span>
              </div>
              <div v-if="f.title" class="scan-finding-title">{{ f.title }}</div>
              <div v-if="f.description" class="scan-finding-desc">{{ f.description }}</div>
              <div v-if="f.remediation" class="scan-finding-fix">
                {{ t('skills.security.fix') }}: {{ f.remediation }}
              </div>
            </li>
          </ul>
          <div v-else-if="detailSkill.securityScanStatus === 'FAILED'" class="scan-findings-empty">
            {{ t('skills.security.noPersistedFindings') }}
          </div>

          <div v-if="getMissingDeps(detailSkill).length > 0" class="detail-security-block">
            <span class="detail-meta-key">{{ t('skills.detail.missingDeps') }}:</span>
            <code>{{ getMissingDeps(detailSkill).join(', ') }}</code>
          </div>

          <div v-if="getRuntimeError(detailSkill)" class="detail-security-block detail-security-error">
            <span class="detail-meta-key">{{ t('skills.detail.runtimeError') }}:</span>
            <span>{{ getRuntimeError(detailSkill) }}</span>
          </div>

          <div v-if="getRuntimePath(detailSkill)" class="detail-security-block">
            <span class="detail-meta-key">{{ t('skills.detail.path') }}:</span>
            <code class="detail-path-code">{{ getRuntimePath(detailSkill) }}</code>
          </div>

          <div v-if="detailSkill.sourceConversationId" class="detail-security-block">
            <span class="detail-meta-key">{{ t('skills.detail.synthesized') }}:</span>
            <span>🤖 {{ t('skills.source.synthesized') }}</span>
          </div>
        </div>

        <!-- RFC-090 §4.2 — Memory tab: cross-reference bound agents +
             pointer to per-agent memory pages. Programmatic
             cross-reference (search MEMORY.md across agents) is a
             future increment; v1 surfaces the agents so the user can
             navigate. -->
        <div v-if="detailTab === 'memory'" class="detail-section">
          <p class="detail-hint">{{ t('skills.detail.memoryHint') }}</p>
          <p v-if="detailEmployeesLoading" class="detail-empty">{{ t('common.loading') }}</p>
          <p v-else-if="detailEmployees.length === 0" class="detail-empty">{{ t('skills.detail.noEmployees') }}</p>
          <ul v-else class="memory-agent-list">
            <li v-for="agent in detailEmployees" :key="agent.id" class="memory-agent-item">
              <span class="memory-agent-icon"><SkillIcon :value="agent.icon" :size="20" :fallback="'🤖'" /></span>
              <div class="memory-agent-info">
                <span class="memory-agent-name">{{ agent.name }}</span>
                <span class="memory-agent-binding" :class="`binding-${agent.binding || 'explicit'}`">
                  {{ agent.binding === 'implicit'
                      ? t('skills.detail.bindingImplicit')
                      : t('skills.detail.bindingExplicit') }}
                </span>
              </div>
              <button class="memory-link-btn" @click="$router.push(`/memory?agentId=${agent.id}`)">
                {{ t('skills.detail.openMemory') }}
              </button>
            </li>
          </ul>
        </div>

        <!-- RFC-090 §11.4 — Lessons tab -->
        <div v-if="detailTab === 'lessons'" class="detail-section">
          <div class="lessons-header">
            <p class="detail-hint">{{ t('skills.detail.lessonsHint') }}</p>
            <button class="lessons-clear-btn" :disabled="!detailLessonsRaw" @click="clearLessons">
              {{ t('skills.detail.clearLessons') }}
            </button>
          </div>
          <p v-if="detailLessonsLoading" class="detail-empty">{{ t('common.loading') }}</p>
          <p v-else-if="!detailLessonsRaw" class="detail-empty">{{ t('skills.detail.noLessons') }}</p>
          <pre v-else class="detail-pre">{{ detailLessonsRaw }}</pre>
        </div>

        <!-- Per-skill secrets — env-var key/value table.
             Plaintext never leaves the server; the panel shows masked
             previews and routes write/delete through the SkillSecretController. -->
        <div v-if="detailTab === 'secrets'" class="detail-section">
          <SkillSecretsPanel
            :skill-id="detailSkill?.id ?? null"
            :visible="detailTab === 'secrets'"
          />
        </div>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- New-skill modal (Layer-1 redesign).
         Reduced to 2 fields. The user's job here is "name it and confirm
         it exists"; everything else is filled in via the detail drawer
         after the row is created. -->
    <div v-if="showModal" class="modal-overlay" @click.self="closeModal">
      <div class="modal modal-slim">
        <div class="modal-header">
          <h2>{{ t('skills.modal.newTitle') }}</h2>
          <button class="modal-close" @click="closeModal">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <p class="modal-hint">{{ t('skills.modal.newSimpleHint') }}</p>
          <!-- Icon picker affordance — tile up top, mirroring the
               drawer's identity-icon-row so users see one consistent
               pattern across create + edit. -->
          <div class="identity-icon-row identity-icon-row--create">
            <SkillIcon :value="newForm.icon" :size="40" class="identity-icon-preview" />
            <div class="identity-icon-meta">
              <span class="identity-icon-label">{{ t('skills.fields.icon') }}</span>
              <code v-if="newForm.icon" class="identity-icon-value">{{ newForm.icon }}</code>
              <span v-else class="identity-icon-empty">{{ t('common.iconPicker.none') }}</span>
            </div>
            <button
              type="button"
              class="detail-edit-btn"
              @click="openIconPickerFor('create')"
            >
              {{ t('common.iconPicker.pickerOpen') }}
            </button>
          </div>
          <div class="form-grid">
            <div class="form-group full-width">
              <label class="form-label">{{ t('skills.fields.name') }} *</label>
              <input
                v-model="newForm.name"
                class="form-input"
                :placeholder="t('skills.placeholders.name')"
                @keydown.enter="createSkillFromModal"
              />
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('skills.fields.description') }}</label>
              <input
                v-model="newForm.description"
                class="form-input"
                :placeholder="t('skills.placeholders.description')"
                @keydown.enter="createSkillFromModal"
              />
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="closeModal">{{ t('common.cancel') }}</button>
          <button class="btn-primary" @click="createSkillFromModal" :disabled="!newForm.name || creating">
            {{ creating ? t('common.loading') : t('skills.actions.createSkill') }}
          </button>
        </div>
      </div>
    </div>

    <!-- Shared icon picker — single instance routed by iconPickerTarget. -->
    <SkillIconPicker
      v-model:visible="iconPickerVisible"
      :model-value="iconPickerTarget === 'create' ? newForm.icon : editForm.icon"
      @apply="onIconPicked"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { skillApi, skillInstallApi } from '@/api/index'
import type { Skill, SkillRuntimeStatus, SkillSecurityFinding } from '@/types/index'
import ImportHubDialog from '@/components/skill/ImportHubDialog.vue'
import PreflightInstallDialog from '@/components/skill/PreflightInstallDialog.vue'
import SkillSecretsPanel from '@/components/skill/SkillSecretsPanel.vue'
import McPagination from '@/components/common/McPagination.vue'
import SkillIcon from '@/components/common/SkillIcon.vue'
import SkillIconPicker from '@/components/common/SkillIconPicker.vue'
import { mcConfirm } from '@/components/common/useConfirm'
import { useSkillName } from '@/composables/useSkillName'

const { t } = useI18n()
const { resolveSkillName, hasI18nName } = useSkillName()
const skills = ref<Skill[]>([])
const total = ref(0)
const counts = ref<Record<string, number>>({})
const runtimeStatusMap = ref<Record<string, SkillRuntimeStatus>>({})
const showModal = ref(false)
const creating = ref(false)
const refreshing = ref(false)
const showImportDialog = ref(false)

const query = reactive({
  page: 1,
  size: 20,
  keyword: '',
  skillType: 'all' as string,
  statusFilter: '' as string,
  sort: 'recommended' as string,
})

/** Per-skill UI state for the RFC-042 §2.3 findings panel. */
const expandedFindings = ref<Record<string, boolean>>({})
const rescanning = ref<Record<string, boolean>>({})

/** RFC-090 Phase 3 — detail drawer state.
 *  Layer-1 redesign: 'overview' replaces 'manifest' (Identity edit + collapsed
 *  manifest dump), and 'body' is a new editable tab for SKILL.md / sourceCode /
 *  raw configJson. The legacy 'manifest' value is still accepted as a starting
 *  tab for back-compat with any deep links that may pass it. */
const detailDrawerVisible = ref(false)
const detailSkill = ref<Skill | null>(null)
const detailTab = ref<'overview' | 'body' | 'manifest' | 'tools' | 'features' | 'security' | 'lessons' | 'secrets' | 'memory'>('overview')
const detailLessonsRaw = ref<string>('')
const detailLessonsLoading = ref(false)
const detailEmployees = ref<Array<{ id: number; name: string; icon?: string; binding?: 'explicit' | 'implicit' }>>([])
const detailEmployeesLoading = ref(false)

/** Inline edit state for the Overview / Body tabs.
 *
 *  Identity edit only covers DB-only display fields (nameZh/nameEn/tags/
 *  description). The other "metadata" columns (icon/version/author/skillType)
 *  are index projections that SkillPackageResolver writes back from
 *  manifest_json on every resolve — exposing them as editable would let
 *  the user perform a save that silently disappears on the next refresh.
 *  See SkillPackageResolver.persistResolutionOutcome for the projection
 *  write-back. The real authoring surface for those is SKILL.md frontmatter,
 *  edited via the Body tab takeover. */
const editingIdentity = ref(false)
const editingBody = ref(false)
const savingEdit = ref(false)
const editForm = ref<{
  nameZh: string
  nameEn: string
  description: string
  tags: string
  icon: string
}>({ nameZh: '', nameEn: '', description: '', tags: '', icon: '' })

/** Icon picker visibility — shared between the create modal and the
 *  drawer Display section. We only ever have one picker open at a time. */
const iconPickerVisible = ref(false)
/** Routes the picker's apply event to either the create form or the
 *  drawer's identity edit, depending on who opened it. */
type IconPickerTarget = 'create' | 'edit'
const iconPickerTarget = ref<IconPickerTarget>('edit')

/** Whether the underlying SKILL.md frontmatter has a declared `icon`.
 *  When true, the resolver will overwrite a row-level icon override on
 *  the next resolve — we surface a warning so the user knows their
 *  edit may be ephemeral and should be made in the body instead. */
const manifestDeclaresIcon = computed(() => {
  const m = detailManifest.value as { icon?: string } | null
  return !!(m && typeof m.icon === 'string' && m.icon.trim())
})
const editBodyForm = ref<{ skillContent: string; sourceCode: string }>({
  skillContent: '',
  sourceCode: '',
})

/** New-skill modal — pared down to the two questions that *must* be answered
 *  at creation time. Everything else is filled in via the drawer. */
const newForm = ref<{ name: string; description: string; icon: string }>({ name: '', description: '', icon: '' })

/** Virtual MCP-derived skills synthesize their id from
 *  {@link McpSkillBridge#VIRTUAL_ID_BASE} (= 9e18). The DB update path
 *  doesn't know about them, so the drawer hides the Edit affordance.
 *  Using string-length is robust against JS number precision loss past 2^53. */
function isVirtualSkillId(id: unknown): boolean {
  if (id === null || id === undefined) return false
  const idStr = String(id)
  return idStr.length >= 19 && idStr.startsWith('9')
}
/** Per-row check used by the card-level configure / delete buttons. */
const isSkillRowVirtual = (skill: { id?: unknown } | null | undefined) => isVirtualSkillId(skill?.id)
const isVirtualSkill = computed(() => isVirtualSkillId(detailSkill.value?.id))
const isBuiltinDetail = computed(() => detailSkill.value?.skillType === 'builtin' || !!detailSkill.value?.builtin)

/** RFC-090 §4.4 — pre-flight dialog state. */
const preflightVisible = ref(false)
const preflightSkillId = ref<number | string | null>(null)
const preflightSkillName = ref('')

function openPreflight(skill: Skill) {
  preflightSkillId.value = skill.id
  preflightSkillName.value = resolveSkillName(skill)
  preflightVisible.value = true
}

/**
 * RFC-090 §4.4 — when ImportHubDialog reports a successful install,
 * reload the catalog and, if the freshly-installed skill is not
 * READY (has unresolved requirements), pop the preflight dialog so
 * the user sees what they need to do next without hunting for the
 * card. Backwards-compatible: if `payload.name` is missing (older
 * dialog versions) we just reload silently.
 */
async function onSkillInstalled(payload?: { name?: string }) {
  await loadAll()
  if (!payload?.name) return
  const installed = skills.value.find(s => s.name === payload.name)
  if (!installed) return
  if (needsSetup(installed)) {
    openPreflight(installed)
  }
}

const detailRuntime = computed(() =>
  detailSkill.value ? runtimeStatusMap.value[detailSkill.value.name] || null : null,
)
const detailManifest = computed(() => detailRuntime.value?.manifest ?? null)
const detailManifestPretty = computed(() =>
  detailManifest.value ? JSON.stringify(detailManifest.value, null, 2) : '',
)
const detailEffectiveTools = computed(() =>
  detailRuntime.value?.effectiveAllowedToolsDisplay || detailRuntime.value?.effectiveAllowedTools || [],
)
const detailToolsCount = computed(() => detailEffectiveTools.value.length)
const detailFeatures = computed(() => {
  const m = detailManifest.value
  if (!m || !m.features) return []
  const statuses = detailRuntime.value?.featureStatuses || {}
  return m.features.map(f => ({ ...f, status: statuses[f.id] || 'UNKNOWN' }))
})
const detailFeaturesCount = computed(() => detailFeatures.value.length)

function openDetailDrawer(
  skill: Skill,
  tab: 'overview' | 'body' | 'tools' | 'features' | 'security' | 'lessons' | 'secrets' | 'memory' = 'overview',
  opts: { editIdentity?: boolean; editBody?: boolean } = {},
) {
  detailSkill.value = skill
  detailTab.value = tab
  detailLessonsRaw.value = ''
  detailEmployees.value = []
  editingIdentity.value = false
  editingBody.value = false
  detailDrawerVisible.value = true
  if (opts.editIdentity) startEditIdentity()
  if (opts.editBody) startEditBody()
}

async function loadDetailEmployees() {
  if (!detailSkill.value) return
  detailEmployeesLoading.value = true
  try {
    const res: any = await skillApi.employees(detailSkill.value.id)
    detailEmployees.value = res?.data || []
  } catch {
    detailEmployees.value = []
  } finally {
    detailEmployeesLoading.value = false
  }
}

async function loadLessons() {
  if (!detailSkill.value) return
  detailLessonsLoading.value = true
  try {
    const res: any = await skillApi.getLessons(detailSkill.value.id)
    detailLessonsRaw.value = res?.data?.raw || ''
  } catch (e: any) {
    detailLessonsRaw.value = ''
    console.warn('[SkillMarket] failed to load lessons', e)
  } finally {
    detailLessonsLoading.value = false
  }
}

async function clearLessons() {
  if (!detailSkill.value) return
  const ok = await mcConfirm({
    title: t('skills.messages.deleteTitle'),
    message: t('skills.detail.clearLessonsConfirm'),
    tone: 'danger',
  })
  if (!ok) return
  try {
    await skillApi.clearLessons(detailSkill.value.id)
    detailLessonsRaw.value = ''
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('skills.messages.deleteFailed'))
  }
}

watch(detailTab, (tab) => {
  // Lazy load LESSONS.md only when the user clicks into that tab; we
  // don't want every drawer open to fire an extra request.
  if (tab === 'lessons' && detailDrawerVisible.value && !detailLessonsRaw.value && !detailLessonsLoading.value) {
    loadLessons()
  }
  if (tab === 'memory' && detailDrawerVisible.value && detailEmployees.value.length === 0 && !detailEmployeesLoading.value) {
    loadDetailEmployees()
  }
})

const categoryTabs = computed(() => [
  { label: t('skills.tabs.all'), value: 'all', icon: '🗂️' },
  { label: t('skills.tabs.builtin'), value: 'builtin', icon: '🔧' },
  { label: t('skills.tabs.mcp'), value: 'mcp', icon: '🔌' },
  // ACP (Agent Communication Protocol) — auto-bridged from
  // Settings ▸ ACP Endpoints; one card per enabled endpoint.
  { label: t('skills.tabs.acp'), value: 'acp', icon: '🤝' },
  { label: t('skills.tabs.dynamic'), value: 'dynamic', icon: '📦' },
])

function getCategoryCount(category: string) {
  return counts.value[category] ?? 0
}

function parseTags(tags: string): string[] {
  if (!tags) return []
  return tags.split(',').map(t => t.trim()).filter(Boolean)
}

onMounted(loadAll)

async function loadAll() {
  // Only block on the skill list itself; counts and runtime status fill in
  // reactively as their fetches resolve. The card's status pill renders a
  // transient "checking" state until runtimeStatusMap populates.
  loadCounts()
  loadRuntimeStatus()
  await loadSkills()
}

/** Coalesce keyword edits into one server call per 300ms so typing doesn't thrash. */
let searchDebounce: ReturnType<typeof setTimeout> | null = null
watch(() => query.keyword, () => {
  if (searchDebounce) clearTimeout(searchDebounce)
  searchDebounce = setTimeout(() => {
    query.page = 1
    loadSkills()
  }, 300)
})

function onTabChange(tab: string) {
  query.skillType = tab
  query.page = 1
  loadSkills()
}

function onFilterChange() {
  query.page = 1
  loadSkills()
}

/** McPagination emits a single change event with both page and size,
 *  so we don't need separate handlers — just reload the list. */
function onPagerChange() {
  loadSkills()
}

async function loadSkills(allowPageClamp = true) {
  try {
    const params: Record<string, unknown> = { page: query.page, size: query.size }
    if (query.keyword) params.keyword = query.keyword.trim()
    if (query.skillType && query.skillType !== 'all') params.skillType = query.skillType
    if (query.sort) params.sort = query.sort
    // Map the single status filter onto the backend's two independent params:
    // enabled (bool) and scanStatus (PASSED/FAILED). scan_failed implies any enabled state.
    if (query.statusFilter === 'enabled') params.enabled = true
    else if (query.statusFilter === 'disabled') params.enabled = false
    else if (query.statusFilter === 'scan_failed') params.scanStatus = 'FAILED'

    const res: any = await skillApi.page(params)
    const data = res.data || {}
    const records: Skill[] = Array.isArray(data.records) ? data.records : []
    // Trust the backend total when it's positive. Only fall back to an inferred
    // floor when the backend reports 0 (broken pagination interceptor) — using
    // Math.max unconditionally produced an off-by-one whenever total was an
    // exact multiple of the page size (the last page is full but has no
    // successor; issue #48).
    const reportedTotal = Number(data.total) || 0
    if (reportedTotal > 0) {
      const pageCount = Math.max(1, Math.ceil(reportedTotal / query.size))
      if (allowPageClamp && query.page > pageCount) {
        query.page = pageCount
        await loadSkills(false)
        return
      }
      total.value = reportedTotal
    } else if (records.length > 0) {
      total.value = records.length >= query.size
        ? query.page * query.size + 1
        : (query.page - 1) * query.size + records.length
      // eslint-disable-next-line no-console
      console.warn('[SkillMarket] backend returned records but total=0; rebuild server JAR to pick up the DbType fix')
    } else {
      if (allowPageClamp && query.page > 1) {
        query.page = 1
        await loadSkills(false)
        return
      }
      total.value = 0
    }
    skills.value = records
  } catch (e) {
    skills.value = []
    total.value = 0
  }
}

async function loadCounts() {
  try {
    const res: any = await skillApi.counts()
    counts.value = res.data || {}
  } catch (e) {
    counts.value = {}
  }
}

async function loadRuntimeStatus() {
  try {
    const res: any = await skillApi.getRuntimeStatus()
    const list: SkillRuntimeStatus[] = res.data || []
    const map: Record<string, SkillRuntimeStatus> = {}
    for (const s of list) {
      map[s.name] = s
    }
    runtimeStatusMap.value = map
  } catch (e) {
    runtimeStatusMap.value = {}
  }
}

function openCreateModal() {
  newForm.value = { name: '', description: '', icon: '' }
  showModal.value = true
}

function closeModal() {
  if (creating.value) return
  showModal.value = false
}

/** Layer-1: create the row with the bare minimum, then drop the user
 *  straight into the drawer in edit mode so they keep configuring without
 *  context-switching back to the card. */
async function createSkillFromModal() {
  if (!newForm.value.name || creating.value) return
  creating.value = true
  try {
    const payload = {
      name: newForm.value.name.trim(),
      description: newForm.value.description.trim(),
      icon: newForm.value.icon || undefined,
      skillType: 'dynamic',
      version: '1.0.0',
      enabled: true,
    }
    const res: any = await skillApi.create(payload)
    showModal.value = false
    await loadAll()
    const created: Skill | undefined = res?.data
    if (created) {
      const fresh = skills.value.find(s => s.id === created.id) || created
      openDetailDrawer(fresh, 'overview', { editIdentity: true })
    }
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('skills.messages.saveFailed'))
  } finally {
    creating.value = false
  }
}

/** Old configure-on-card button now lands in the drawer Overview tab in
 *  edit mode — keeps the surface contract but skips the modal trip.
 *  Virtual MCP/ACP-derived skills aren't editable; the card-level button
 *  is hidden for them, but if any other call path reaches here with a
 *  virtual skill, fall through to view mode rather than opening an edit
 *  surface that will 4xx on save. */
function openEditFromCard(skill: Skill) {
  const opts = isSkillRowVirtual(skill) ? undefined : { editIdentity: true }
  openDetailDrawer(skill, 'overview', opts)
}

// ==================== Inline edit (Overview / Body) ====================

function startEditIdentity() {
  if (!detailSkill.value) return
  const s = detailSkill.value
  editForm.value = {
    nameZh: s.nameZh || '',
    nameEn: s.nameEn || '',
    description: s.description || '',
    tags: s.tags || '',
    icon: s.icon || '',
  }
  editingIdentity.value = true
}

function openIconPickerFor(target: IconPickerTarget) {
  iconPickerTarget.value = target
  iconPickerVisible.value = true
}

function onIconPicked(value: string) {
  if (iconPickerTarget.value === 'edit') {
    editForm.value.icon = value
  } else {
    newForm.value.icon = value
  }
}

function cancelEditIdentity() {
  editingIdentity.value = false
}

async function saveIdentity() {
  if (!detailSkill.value || savingEdit.value) return
  savingEdit.value = true
  try {
    // PUT /skills/{id} only updates non-null fields (MyBatis Plus default
    // FieldStrategy=NOT_NULL on the entity), so we send only the DB-only
    // display slice. Manifest-projected fields (icon/version/author/
    // skillType) are deliberately excluded — they're owned by manifest_json
    // and re-projected by SkillPackageResolver on every resolve.
    const payload: Record<string, unknown> = {
      nameZh: editForm.value.nameZh,
      nameEn: editForm.value.nameEn,
      description: editForm.value.description,
      tags: editForm.value.tags,
      // Icon is technically a manifest-projected field, but the
      // resolver only overwrites when the manifest declares one
      // (SkillPackageResolver.java:185). Sending it here lets users
      // override icons for skills whose SKILL.md has no `icon:` and
      // — for skills that do declare it — the warning above tells
      // them to expect re-projection.
      icon: editForm.value.icon,
    }
    const res: any = await skillApi.update(detailSkill.value.id, payload)
    const updated: Skill | undefined = res?.data
    if (updated) {
      patchSkillInPlace(updated)
      detailSkill.value = { ...detailSkill.value, ...updated }
    }
    editingIdentity.value = false
    ElMessage.success(t('skills.messages.saveSuccess'))
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('skills.messages.saveFailed'))
  } finally {
    savingEdit.value = false
  }
}

function startEditBody() {
  if (!detailSkill.value) return
  const s = detailSkill.value
  editBodyForm.value = {
    skillContent: s.skillContent || '',
    sourceCode: s.sourceCode || '',
  }
  editingBody.value = true
}

function cancelEditBody() {
  editingBody.value = false
}

async function saveBody() {
  if (!detailSkill.value || savingEdit.value) return
  savingEdit.value = true
  try {
    const payload: Record<string, unknown> = {
      skillContent: editBodyForm.value.skillContent,
    }
    if (detailSkill.value.skillType === 'dynamic') {
      payload.sourceCode = editBodyForm.value.sourceCode
    }
    const res: any = await skillApi.update(detailSkill.value.id, payload)
    const updated: Skill | undefined = res?.data
    if (updated) {
      patchSkillInPlace(updated)
      detailSkill.value = { ...detailSkill.value, ...updated }
    }
    editingBody.value = false
    // Body change → next resolve re-projects icon/version/author from the
    // new frontmatter, so refresh runtime status to pick those up.
    loadRuntimeStatus()
    ElMessage.success(t('skills.detail.sourceSavedReprojection'))
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('skills.messages.saveFailed'))
  } finally {
    savingEdit.value = false
  }
}

function closeDetailDrawer() {
  // Drop the takeover too — re-opening the same skill should land in
  // the read view, not in a half-saved edit state.
  if (savingEdit.value) return
  editingIdentity.value = false
  editingBody.value = false
  detailDrawerVisible.value = false
}

function patchSkillInPlace(updated: Skill) {
  const idx = skills.value.findIndex(s => s.id === updated.id)
  if (idx >= 0) skills.value.splice(idx, 1, { ...skills.value[idx], ...updated })
}

async function deleteSkill(idOrSkill: string | number | Skill) {
  // RFC-090 §14.5 — UI "Delete" routes to uninstall, not hard-delete:
  //   - DELETE /skills/install/{name} archives the workspace + soft-delete row
  //   - DELETE /skills/{id} (admin "彻底删除") stays hidden in the UI
  // Resolve to the skill record so we can call the uninstall path by name.
  const skill: Skill | undefined = typeof idOrSkill === 'object'
    ? idOrSkill
    : skills.value.find(s => s.id === idOrSkill)
  if (!skill) return
  const ok = await mcConfirm({
    title: t('skills.messages.deleteTitle'),
    message: t('skills.messages.deleteConfirm'),
    tone: 'danger',
  })
  if (!ok) return
  try {
    await skillInstallApi.uninstall(skill.name)
    await loadAll()
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('skills.messages.deleteFailed'))
  }
}

async function toggleSkill(skill: Skill) {
  // Issue #83: short-circuit if a programmatic caller reaches this for a
  // virtual skill (the UI hides the toggle, but defense-in-depth keeps the
  // toast accurate when the backend would otherwise return err.skill.not_found
  // on builds that pre-date the rejectVirtualSkillMutation guard).
  if (isSkillRowVirtual(skill)) {
    ElMessage.warning(t('skills.virtualReadonlyHint'))
    return
  }
  try {
    await skillApi.toggle(skill.id, !skill.enabled)
    await loadAll()
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('skills.messages.toggleFailed'))
  }
}

async function handleRefreshRuntime() {
  refreshing.value = true
  try {
    await skillApi.refreshRuntime()
    await loadRuntimeStatus()
    ElMessage.success(t('skills.refreshSuccess'))
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('skills.refreshFailed'))
  } finally {
    refreshing.value = false
  }
}

// ==================== RFC-042 §2.3 — persisted scan findings ====================

/** Parse the DB-persisted JSON findings array. Returns [] on any parse error. */
function parsedFindings(skill: Skill): SkillSecurityFinding[] {
  const raw = skill.securityScanResult
  if (!raw) return []
  try {
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? (arr as SkillSecurityFinding[]) : []
  } catch {
    return []
  }
}

function toggleFindings(skill: Skill) {
  const key = String(skill.id)
  expandedFindings.value = { ...expandedFindings.value, [key]: !expandedFindings.value[key] }
}

async function rescanSkill(skill: Skill) {
  const key = String(skill.id)
  rescanning.value = { ...rescanning.value, [key]: true }
  try {
    const res: any = await skillApi.rescan(skill.id)
    const updated: Skill | undefined = res?.data
    if (updated) {
      // Patch the row in-place so the panel updates without a full page reload.
      const idx = skills.value.findIndex(s => s.id === skill.id)
      if (idx >= 0) skills.value.splice(idx, 1, { ...skills.value[idx], ...updated })
      ElMessage.success(
        updated.securityScanStatus === 'FAILED'
          ? t('skills.security.rescanStillFailed')
          : t('skills.security.rescanPassed')
      )
    }
    // Refresh runtime status too so the in-memory badges stay in sync.
    await loadRuntimeStatus()
  } catch (e: any) {
    ElMessage.error(typeof e === 'string' ? e : e?.message || t('skills.security.rescanFailed'))
  } finally {
    rescanning.value = { ...rescanning.value, [key]: false }
  }
}

function formatScanTime(iso: string): string {
  try {
    const d = new Date(iso)
    if (isNaN(d.getTime())) return iso
    return d.toLocaleString()
  } catch {
    return iso
  }
}

// ==================== Runtime Display Helpers ====================

function getRuntimeStatus(skill: Skill): SkillRuntimeStatus | null {
  return runtimeStatusMap.value[skill.name] || null
}

function getRuntimeLabel(skill: Skill): string {
  if (!skill.enabled) return 'Disabled'
  const rt = getRuntimeStatus(skill)
  if (!rt) return 'Unknown'
  // Use computed label from backend if available
  if (rt.runtimeStatusLabel) return rt.runtimeStatusLabel
  if (rt.securityBlocked) return 'Security Blocked'
  if (rt.dependencyReady === false) return 'Dependencies Missing'
  if (rt.source === 'directory' && rt.runtimeAvailable) return 'Directory Active'
  if (rt.source === 'convention' && rt.runtimeAvailable) return 'Convention Active'
  if (rt.source === 'database' && rt.runtimeAvailable && rt.configuredSkillDir) return 'Database Fallback'
  if (rt.source === 'database' && rt.runtimeAvailable) return 'Database Active'
  if (!rt.runtimeAvailable) return 'Unresolved'
  return rt.source
}

function getRuntimeBadgeClass(skill: Skill): string {
  if (!skill.enabled) return 'rt-disabled'
  const rt = getRuntimeStatus(skill)
  if (!rt) return 'rt-unknown'
  if (rt.securityBlocked) return 'rt-blocked'
  if (rt.dependencyReady === false) return 'rt-deps-missing'
  if (rt.source === 'directory' && rt.runtimeAvailable) return 'rt-directory'
  if (rt.source === 'convention' && rt.runtimeAvailable) return 'rt-convention'
  if (rt.source === 'database' && rt.runtimeAvailable && rt.configuredSkillDir) return 'rt-fallback'
  if (rt.source === 'database' && rt.runtimeAvailable) return 'rt-database'
  if (!rt.runtimeAvailable) return 'rt-error'
  return 'rt-unknown'
}

function getRuntimePath(skill: Skill): string {
  const rt = getRuntimeStatus(skill)
  if (!rt) return ''
  return rt.skillDirPath || rt.configuredSkillDir || ''
}

function getRuntimeError(skill: Skill): string {
  if (!skill.enabled) return ''
  const rt = getRuntimeStatus(skill)
  return rt?.resolutionError || ''
}

// ==================== Security tab helpers (drawer) ====================

function getScanPillCls(skill: Skill): string {
  if (skill.securityScanStatus === 'FAILED') return 'st-blocked'
  if (skill.securityScanStatus === 'PASSED') return 'st-ready'
  return 'st-disabled'
}

function getScanPillLabel(skill: Skill): string {
  if (skill.securityScanStatus === 'FAILED') return t('skills.security.scanFailed')
  if (skill.securityScanStatus === 'PASSED') return t('skills.security.scanned')
  return t('skills.security.notScanned')
}

// ==================== Unified Status Pill ====================

/**
 * Single status pill for the card. Folds runtime / security / dependency /
 * features matrices into one of six mutually-exclusive states so the card
 * surfaces *one* color, not five. Detail-drawer Security tab carries the
 * granular breakdown for users who want it.
 */
function getStatusPill(skill: Skill): { label: string; cls: string } {
  if (!skill.enabled) {
    return { label: t('skills.status.disabled'), cls: 'st-disabled' }
  }
  const rt = getRuntimeStatus(skill)
  if (!rt) {
    return { label: t('skills.status.checking'), cls: 'st-checking' }
  }
  if (rt.securityBlocked || skill.securityScanStatus === 'FAILED') {
    return { label: t('skills.status.blocked'), cls: 'st-blocked' }
  }
  if (needsSetup(skill)) {
    return { label: t('skills.status.setupNeeded'), cls: 'st-setup' }
  }
  if (!rt.runtimeAvailable) {
    return { label: t('skills.status.unresolved'), cls: 'st-error' }
  }
  return { label: t('skills.status.ready'), cls: 'st-ready' }
}

// ==================== Security & Dependency Helpers ====================

function getSecurityBadge(skill: Skill): { label: string; cls: string } | null {
  if (!skill.enabled) return null
  const rt = getRuntimeStatus(skill)
  if (!rt) return null
  if (rt.securityBlocked) return { label: 'Security Blocked', cls: 'rt-blocked' }
  if (rt.securityFindings && rt.securityFindings.length > 0) {
    return { label: `Security Warning (${rt.securityFindings.length})`, cls: 'rt-sec-warning' }
  }
  return null
}

function getDependencyBadge(skill: Skill): { label: string; cls: string } | null {
  if (!skill.enabled) return null
  const rt = getRuntimeStatus(skill)
  if (!rt) return null
  if (rt.dependencyReady === false) {
    const count = rt.missingDependencies?.length || 0
    return { label: `Deps Missing (${count})`, cls: 'rt-deps-missing' }
  }
  return null
}

/**
 * RFC-090 §14.1 — when a manifest declares a features[] matrix, surface
 * "Setup Needed (M/N)" so the user sees partial readiness instead of a
 * binary "all-or-nothing" deps badge. Returns null when the manifest has
 * no explicit features (legacy skills or single-feature shape) — the
 * existing dependency badge already covers that case.
 */
function getFeaturesBadge(skill: Skill): { label: string; cls: string } | null {
  if (!skill.enabled) return null
  const rt = getRuntimeStatus(skill)
  if (!rt || !rt.manifest) return null
  const declaredFeatures = rt.manifest.features || []
  if (declaredFeatures.length === 0) return null
  const statuses = rt.featureStatuses || {}
  const total = declaredFeatures.length
  const ready = (rt.activeFeatures || []).length
  if (ready === total) {
    return { label: `${ready}/${total} ready`, cls: 'rt-features-ready' }
  }
  if (ready === 0) {
    return { label: `Setup Needed (0/${total})`, cls: 'rt-deps-missing' }
  }
  // Mixed: at least one feature is not READY.
  // Distinguish UNSUPPORTED vs SETUP_NEEDED to color appropriately.
  const anyUnsupported = Object.values(statuses).some(s => s === 'UNSUPPORTED')
  return {
    label: `Setup Needed (${ready}/${total})`,
    cls: anyUnsupported ? 'rt-features-mixed' : 'rt-deps-missing',
  }
}

function getMissingDeps(skill: Skill): string[] {
  const rt = getRuntimeStatus(skill)
  return rt?.missingDependencies || []
}

function getSecurityFindingsSummary(skill: Skill): string {
  const rt = getRuntimeStatus(skill)
  if (!rt || !rt.securityFindings || rt.securityFindings.length === 0) return ''
  const critical = rt.securityFindings.filter((f: SkillSecurityFinding) => f.severity === 'CRITICAL').length
  const high = rt.securityFindings.filter((f: SkillSecurityFinding) => f.severity === 'HIGH').length
  const medium = rt.securityFindings.filter((f: SkillSecurityFinding) => f.severity === 'MEDIUM').length
  const parts: string[] = []
  if (critical > 0) parts.push(`${critical} critical`)
  if (high > 0) parts.push(`${high} high`)
  if (medium > 0) parts.push(`${medium} medium`)
  return parts.length > 0 ? `Findings: ${parts.join(', ')}` : ''
}

// ==================== UI Helpers ====================

function getSourceBadge(skill: Skill): string {
  const rt = getRuntimeStatus(skill)
  if (!rt) return ''
  if (rt.source === 'convention') return '📁'
  // 检查 configJson 中的 source.type
  try {
    const config = skill.configJson ? JSON.parse(skill.configJson) : null
    const sourceType = config?.source?.type || config?.upstream
    if (sourceType === 'github') return '🐙'
    if (sourceType === 'clawhub') return '🐾'
  } catch { /* ignore */ }
  return ''
}

/**
 * RFC-090 §4.2 — Source label for the skill card.
 *
 * Derivation precedence:
 *   1. builtin=true       → "Built-in"
 *   2. skillType=mcp      → "MCP"
 *   3. skillType=acp      → "ACP" (Phase 7)
 *   4. sourceConversationId set → "AI Synthesized" (RFC-023)
 *   5. configJson.source.type / upstream → "ClawHub" / "GitHub"
 *   6. fallback           → "Local"
 */
function getSourceLabel(skill: Skill): string {
  if (skill.builtin) return t('skills.source.builtin')
  if (skill.skillType === 'mcp') return 'MCP'
  if (skill.skillType === 'acp') return 'ACP'
  if (skill.sourceConversationId) return t('skills.source.synthesized')
  try {
    const config = skill.configJson ? JSON.parse(skill.configJson) : null
    const sourceType = config?.source?.type || config?.upstream
    if (sourceType === 'clawhub') return 'ClawHub'
    if (sourceType === 'github') return 'GitHub'
  } catch { /* ignore */ }
  return t('skills.source.local')
}

function getSourceClass(skill: Skill): string {
  if (skill.builtin) return 'src-builtin'
  if (skill.skillType === 'mcp' || skill.skillType === 'acp') return 'src-protocol'
  if (skill.sourceConversationId) return 'src-synth'
  return 'src-local'
}

/**
 * RFC-090 §4.2 — does this skill need the [Set Up] button surfaced?
 * Yes iff: enabled, not security-blocked, AND either:
 *   - manifest features exist with at least one not-READY, OR
 *   - legacy dependencyReady is false.
 */
function needsSetup(skill: Skill): boolean {
  if (!skill.enabled) return false
  const rt = getRuntimeStatus(skill)
  if (!rt) return false
  if (rt.securityBlocked) return false
  if (rt.manifest && Array.isArray(rt.manifest.features) && rt.manifest.features.length > 0) {
    const total = rt.manifest.features.length
    const ready = (rt.activeFeatures || []).length
    return ready < total
  }
  return rt.dependencyReady === false
}

function getSkillIcon(type: string) {
  return { builtin: '🔧', mcp: '🔌', acp: '🤝', dynamic: '📦' }[type] ?? '🛠️'
}

function getSkillIconBg(type: string) {
  return { builtin: 'bg-blue', mcp: 'bg-purple', acp: 'bg-orange', dynamic: 'bg-green' }[type] ?? 'bg-gray'
}

function getSkillTypeBadge(type: string) {
  return { builtin: 'badge-blue', mcp: 'badge-purple', acp: 'badge-orange', dynamic: 'badge-green' }[type] ?? 'badge-gray'
}

function getSkillTypeLabel(type: string) {
  const map: Record<string, string> = {
    builtin: t('skills.types.builtin'),
    mcp: t('skills.types.mcp'),
    acp: t('skills.types.acp'),
    dynamic: t('skills.types.dynamic'),
  }
  return map[type] ?? type
}
</script>

<style scoped>
.skills-page { gap: 18px; }
.header-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.btn-primary { display: flex; align-items: center; gap: 6px; padding: 10px 16px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: white; border: none; border-radius: 14px; font-size: 14px; font-weight: 600; cursor: pointer; transition: background 0.15s; box-shadow: var(--mc-shadow-soft); }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { display: flex; align-items: center; gap: 6px; padding: 9px 14px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 12px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }
.btn-secondary:disabled { opacity: 0.6; cursor: not-allowed; }

/* 分类 Tab */
.category-tabs { display: flex; gap: 8px; flex-wrap: wrap; padding: 14px; }
.cat-tab { display: flex; align-items: center; gap: 6px; padding: 9px 16px; border: 1px solid var(--mc-border); background: var(--mc-bg-muted); border-radius: 999px; font-size: 13px; color: var(--mc-text-secondary); cursor: pointer; transition: all 0.15s; font-weight: 600; }
.cat-tab:hover { background: var(--mc-bg-sunken); }
.cat-tab.active { background: var(--mc-primary-bg); border-color: var(--mc-primary); color: var(--mc-primary); font-weight: 500; }
.cat-icon { font-size: 14px; }
.cat-count { background: var(--mc-bg-sunken); color: var(--mc-text-secondary); padding: 1px 6px; border-radius: 10px; font-size: 11px; }
.cat-tab.active .cat-count { background: rgba(217, 119, 87, 0.2); color: var(--mc-primary); }

/* Search and filter bar. */
.skill-filter-bar {
  display: flex;
  gap: 10px;
  padding: 10px 14px;
  align-items: center;
  backdrop-filter: blur(14px) saturate(1.1);
  -webkit-backdrop-filter: blur(14px) saturate(1.1);
}
.skill-search-input,
.skill-status-filter {
  height: 34px;
  border: 1px solid transparent;
  background: rgba(255, 255, 255, 0.45);
  border-radius: 10px;
  font-size: 13px;
  color: var(--mc-text-primary);
  outline: none;
  transition: background 0.18s, border-color 0.18s, box-shadow 0.18s;
}
html.dark .skill-search-input,
html.dark .skill-status-filter {
  background: rgba(255, 255, 255, 0.06);
}
.skill-search-input { flex: 1; padding: 0 12px; }
.skill-status-filter { padding: 0 10px; cursor: pointer; min-width: 140px; }
.skill-search-input:hover,
.skill-status-filter:hover {
  background: rgba(255, 255, 255, 0.65);
}
html.dark .skill-search-input:hover,
html.dark .skill-status-filter:hover {
  background: rgba(255, 255, 255, 0.1);
}
.skill-search-input:focus,
.skill-status-filter:focus {
  border-color: rgba(217, 119, 87, 0.45);
  background: rgba(255, 255, 255, 0.7);
  box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.12);
}
html.dark .skill-search-input:focus,
html.dark .skill-status-filter:focus {
  background: rgba(255, 255, 255, 0.12);
}

.skill-pagination { margin-top: 18px; display: flex; justify-content: center; }

/* Security scan findings panel. */
.scan-badge-button {
  border: none;
  cursor: pointer;
  font: inherit;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px 2px 8px;
}
.scan-badge-button:hover { filter: brightness(0.97); }
.scan-badge-chevron { font-size: 10px; opacity: 0.75; }

.scan-findings-panel {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(255, 95, 86, 0.08);
  border: 1px solid rgba(255, 95, 86, 0.22);
  backdrop-filter: blur(8px) saturate(1.05);
  -webkit-backdrop-filter: blur(8px) saturate(1.05);
}
html.dark .scan-findings-panel {
  background: rgba(255, 95, 86, 0.12);
  border-color: rgba(255, 95, 86, 0.28);
}
.scan-findings-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}
.scan-findings-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-primary);
}
.scan-findings-time {
  font-weight: 400;
  font-size: 11px;
  color: var(--mc-text-tertiary);
}
.scan-rescan-btn {
  height: 26px;
  padding: 0 10px;
  border-radius: 8px;
  border: 1px solid transparent;
  background: rgba(255, 255, 255, 0.55);
  color: var(--mc-text-primary);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}
html.dark .scan-rescan-btn {
  background: rgba(255, 255, 255, 0.08);
}
.scan-rescan-btn:hover:not(:disabled) {
  background: var(--mc-primary);
  color: #fff;
}
.scan-rescan-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.scan-findings-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.scan-finding-item {
  padding: 7px 9px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.45);
  border-left: 3px solid var(--mc-border);
  font-size: 12px;
  line-height: 1.5;
}
html.dark .scan-finding-item { background: rgba(255, 255, 255, 0.05); }
.scan-finding-item.sev-critical { border-left-color: #d32f2f; }
.scan-finding-item.sev-high     { border-left-color: #f57c00; }
.scan-finding-item.sev-medium   { border-left-color: #fbc02d; }
.scan-finding-item.sev-low      { border-left-color: #689f38; }
.scan-finding-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
}
.scan-finding-sev { font-weight: 700; color: var(--mc-text-primary); }
.scan-finding-id { color: var(--mc-text-secondary); }
.scan-finding-loc { color: var(--mc-text-tertiary); }
.scan-finding-title { font-weight: 600; margin-top: 3px; color: var(--mc-text-primary); }
.scan-finding-desc { color: var(--mc-text-secondary); margin-top: 2px; }
.scan-finding-fix  { color: var(--mc-text-secondary); margin-top: 4px; font-style: italic; }
.scan-findings-empty {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  font-style: italic;
}

/* 技能网格 */
.skill-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 18px; }
.skill-card {
  padding: 18px;
  transition: all 0.15s;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 200px;
  cursor: pointer;
  outline: none;
}
.skill-card:hover { border-color: var(--mc-primary-light); box-shadow: var(--mc-shadow-medium); transform: translateY(-2px); }
.skill-card:focus-visible { border-color: var(--mc-primary); box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.18); }
.skill-card.disabled { opacity: 0.6; }
.skill-header { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 0; }
.skill-icon-wrap { width: 44px; height: 44px; border-radius: 14px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.bg-blue { background: var(--mc-primary-bg); }
.bg-purple { background: var(--mc-primary-bg); }
.bg-orange { background: var(--mc-primary-bg); }
.bg-green { background: var(--mc-primary-bg); }
.bg-gray { background: var(--mc-bg-sunken); }
.skill-icon { font-size: 20px; }
.skill-meta { flex: 1; overflow: hidden; }
.skill-name { font-size: 16px; font-weight: 700; color: var(--mc-text-primary); margin: 0 0 2px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
/* RFC-042 §2.2 — slug printed under the i18n display name when they differ */
.skill-slug { font-size: 11px; color: var(--mc-text-tertiary); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; margin: 0 0 4px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.skill-version { font-size: 11px; color: var(--mc-text-tertiary); margin-left: auto; }

/* Phase 1 slim — single status row replaces the runtime/security/deps badge wall */
.skill-status-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin: 0; }
.status-pill {
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.02em;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.status-pill.st-ready    { background: rgba(34, 197, 94, 0.12); color: #16a34a; }
.status-pill.st-setup    { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.status-pill.st-blocked  { background: var(--mc-danger-bg); color: var(--mc-danger); }
.status-pill.st-error    { background: var(--mc-danger-bg); color: var(--mc-danger); }
.status-pill.st-disabled { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.status-pill.st-checking { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); font-weight: 500; }
:root.dark .status-pill.st-ready { background: rgba(34, 197, 94, 0.2); color: #4ade80; }
.toggle-switch { position: relative; display: inline-block; width: 36px; height: 20px; cursor: pointer; flex-shrink: 0; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--mc-border); border-radius: 20px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 14px; height: 14px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(16px); }
/* Issue #83: padlock placeholder where the toggle would be on virtual MCP/ACP rows. */
.virtual-toggle-hint {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 20px;
  color: var(--mc-text-quaternary);
  cursor: help;
  flex-shrink: 0;
  border-radius: 6px;
}
.virtual-toggle-hint:hover { color: var(--mc-text-tertiary); background: var(--mc-bg-sunken); }
.skill-desc { font-size: 13px; color: var(--mc-text-secondary); margin: 0 0 10px; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; flex: 1; }

/* Runtime Status */
.skill-runtime-row { display: flex; align-items: center; gap: 8px; margin: 0 0 6px; min-height: 20px; flex-wrap: wrap; }
.runtime-badge { padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; letter-spacing: 0.02em; }
.rt-directory { background: var(--mc-primary-bg); color: var(--mc-primary); }
.rt-convention { background: var(--mc-primary-bg); color: var(--mc-primary); }
.source-badge { font-size: 13px; cursor: default; }
.rt-database { background: var(--mc-primary-bg); color: var(--mc-primary); }
.rt-fallback { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.rt-error { background: var(--mc-danger-bg); color: var(--mc-danger); }
.rt-blocked { background: var(--mc-danger-bg); color: var(--mc-danger); font-weight: 600; }
.rt-synthesized { background: #f0f0ff; color: #6366f1; }
:root.dark .rt-synthesized { background: rgba(99, 102, 241, 0.15); color: #818cf8; }
.rt-sec-warning { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.rt-deps-missing { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
/* RFC-090 §14.1 — features 矩阵徽标 */
.rt-features-ready { background: rgba(34, 197, 94, 0.12); color: #16a34a; }
.rt-features-mixed { background: rgba(99, 102, 241, 0.12); color: #6366f1; }

/* RFC-090 §4.2 — Source label + Used-by + Lessons count */
.source-label { padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; letter-spacing: 0.04em; }
.source-label.src-builtin { background: rgba(34, 197, 94, 0.12); color: #16a34a; }
.source-label.src-protocol { background: rgba(99, 102, 241, 0.12); color: #6366f1; }
.source-label.src-synth { background: rgba(168, 85, 247, 0.12); color: #a855f7; }
.source-label.src-local { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.usedby-badge, .lessons-badge { padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600; background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }
.lessons-badge { cursor: pointer; }
.lessons-badge:hover { background: var(--mc-primary-bg); color: var(--mc-primary); }

/* RFC-090 §4.2 — [Set Up] button highlight */
.skill-btn-setup { background: var(--mc-primary-bg); color: var(--mc-primary); border-color: rgba(217, 109, 70, 0.18); font-weight: 600; }
.skill-btn-setup:hover { background: var(--mc-primary); color: white; }
:root.dark .rt-features-ready { background: rgba(34, 197, 94, 0.2); color: #4ade80; }
:root.dark .rt-features-mixed { background: rgba(129, 140, 248, 0.18); color: #a5b4fc; }
.rt-disabled { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.rt-unknown { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.skill-source-path { font-size: 11px; color: var(--mc-text-tertiary); font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 200px; }
.runtime-error { font-size: 11px; color: var(--mc-text-tertiary); margin: 0 0 6px; line-height: 1.4; font-style: italic; }
.runtime-deps-missing { font-size: 11px; color: var(--mc-primary); margin: 0 0 4px; line-height: 1.4; font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; }
.runtime-security-detail { font-size: 11px; color: var(--mc-primary-hover); margin: 0 0 4px; line-height: 1.4; }

/* Tags */
.skill-tags { display: flex; gap: 4px; flex-wrap: wrap; margin-bottom: 10px; }
.skill-tag { padding: 2px 8px; background: var(--mc-bg-sunken); color: var(--mc-text-secondary); border-radius: 4px; font-size: 11px; }

/* Footer */
.skill-footer { display: flex; align-items: center; justify-content: space-between; border-top: 1px solid var(--mc-border-light); padding-top: 12px; }
.skill-author { font-size: 12px; color: var(--mc-text-tertiary); }
.skill-actions { display: flex; gap: 6px; }
.skill-btn { display: flex; align-items: center; gap: 4px; padding: 7px 11px; border: 1px solid var(--mc-border); background: var(--mc-bg-muted); border-radius: 10px; font-size: 12px; color: var(--mc-text-primary); cursor: pointer; transition: all 0.15s; font-weight: 600; }
.skill-btn:hover { background: var(--mc-bg-sunken); }
.skill-btn.danger:hover { background: var(--mc-danger-bg); border-color: var(--mc-danger); color: var(--mc-danger); }

/* Empty */
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 80px 20px; text-align: center; }
.empty-icon { font-size: 48px; margin-bottom: 16px; }
.empty-state h3 { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0 0 8px; }
.empty-state p { font-size: 14px; color: var(--mc-text-tertiary); margin: 0; }

/* Modal */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; width: 100%; max-width: 580px; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,0.15); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.modal-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); display: flex; align-items: center; justify-content: center; border-radius: 6px; }
.modal-close:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.modal-body { flex: 1; overflow-y: auto; padding: 20px 24px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.form-group { display: flex; flex-direction: column; gap: 6px; }
.form-group.full-width { grid-column: 1 / -1; }
.form-label { font-size: 13px; font-weight: 500; color: var(--mc-text-secondary); display: flex; align-items: baseline; gap: 6px; }
.form-hint { font-size: 11px; font-weight: 400; color: var(--mc-text-tertiary); }
.form-input, .form-textarea { padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; color: var(--mc-text-primary); outline: none; transition: border-color 0.15s; background: var(--mc-bg-sunken); }
.form-input:focus, .form-textarea:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }
.form-input:disabled, .form-textarea:disabled { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); cursor: not-allowed; }
.form-textarea { resize: vertical; font-family: inherit; }
.form-textarea.code { font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; font-size: 13px; background: var(--mc-bg-sunken); }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 16px 24px; border-top: 1px solid var(--mc-border-light); }

@media (max-width: 900px) {
  .header-actions {
    width: 100%;
  }
}

/* RFC-090 Phase 3 — detail drawer */
.detail-drawer { padding: 0 16px 16px; display: flex; flex-direction: column; gap: 16px; }
.detail-tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--mc-border-light); padding-bottom: 4px; }
.detail-tab { padding: 8px 14px; border: none; background: none; cursor: pointer; font-size: 13px; font-weight: 500; color: var(--mc-text-secondary); border-radius: 8px 8px 0 0; display: inline-flex; align-items: center; gap: 6px; }
.detail-tab:hover { color: var(--mc-text-primary); background: var(--mc-bg-muted); }
.detail-tab.active { color: var(--mc-primary); background: var(--mc-primary-bg); border-bottom: 2px solid var(--mc-primary); margin-bottom: -1px; font-weight: 600; }
.tab-count { font-size: 10px; padding: 1px 6px; border-radius: 10px; background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); font-weight: 600; }
.detail-tab.active .tab-count { background: var(--mc-primary); color: white; }
.detail-section { padding: 4px 0; }
.detail-empty { color: var(--mc-text-tertiary); font-size: 13px; font-style: italic; }
.detail-pre { background: var(--mc-bg-sunken); padding: 12px; border-radius: 8px; max-height: 480px; overflow: auto; font-size: 12px; line-height: 1.5; color: var(--mc-text-primary); font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; white-space: pre-wrap; word-break: break-word; }
.detail-tool-list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 6px; }
.detail-tool-item code { display: block; padding: 6px 10px; background: var(--mc-bg-sunken); border-radius: 8px; font-size: 12px; color: var(--mc-text-primary); }
.detail-hint { margin-top: 12px; font-size: 12px; color: var(--mc-text-tertiary); line-height: 1.5; }
.detail-feature-list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 10px; }
.detail-feature-item { padding: 12px; border: 1px solid var(--mc-border-light); border-radius: 12px; background: var(--mc-bg-muted); }
.detail-feature-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.detail-feature-id { font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; font-size: 12px; font-weight: 600; color: var(--mc-text-primary); }
.detail-feature-status { font-size: 10px; padding: 2px 8px; border-radius: 999px; font-weight: 700; letter-spacing: 0.04em; }
.feat-ready { background: rgba(34, 197, 94, 0.12); color: #16a34a; }
.feat-setup_needed { background: var(--mc-primary-bg); color: var(--mc-primary-hover); }
.feat-unsupported { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.feat-unknown { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
:root.dark .feat-ready { background: rgba(34, 197, 94, 0.2); color: #4ade80; }
.detail-feature-label { font-size: 13px; color: var(--mc-text-secondary); margin: 4px 0 6px; }
.detail-feature-meta { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; margin: 4px 0; font-size: 11px; }
.detail-meta-key { color: var(--mc-text-tertiary); margin-right: 4px; }
.detail-feature-tag { padding: 2px 6px; background: var(--mc-bg-elevated); border-radius: 4px; font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace; color: var(--mc-text-primary); }
.detail-feature-fallback { font-size: 11px; color: var(--mc-primary-hover); margin-top: 6px; font-style: italic; }

/* RFC-090 §4.2 Memory tab — bound agents list */
.memory-agent-list { list-style: none; padding: 0; margin: 8px 0 0; display: flex; flex-direction: column; gap: 8px; }
.memory-agent-item { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border: 1px solid var(--mc-border-light); border-radius: 10px; background: var(--mc-bg-muted); }
.memory-agent-icon { font-size: 20px; }
.memory-agent-info { flex: 1; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.memory-agent-name { font-weight: 600; color: var(--mc-text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.memory-agent-binding { font-size: 10px; padding: 1px 6px; border-radius: 999px; font-weight: 700; letter-spacing: 0.04em; align-self: flex-start; }
.binding-explicit { background: var(--mc-primary-bg); color: var(--mc-primary); }
.binding-implicit { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.memory-link-btn { padding: 4px 10px; border: 1px solid var(--mc-border); background: var(--mc-bg-elevated); color: var(--mc-primary); border-radius: 8px; font-size: 12px; cursor: pointer; font-weight: 500; }
.memory-link-btn:hover { background: var(--mc-primary-bg); border-color: var(--mc-primary); }

/* Drawer Security tab */
.detail-security-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.detail-security-block {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  font-size: 12px;
  line-height: 1.5;
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}
.detail-security-block code {
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  background: var(--mc-bg-sunken);
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11px;
  color: var(--mc-text-primary);
  word-break: break-all;
}
.detail-security-block.detail-security-error {
  background: var(--mc-danger-bg);
  border-color: rgba(239, 68, 68, 0.22);
  color: var(--mc-danger);
}
.detail-path-code { max-width: 100%; }

.lessons-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.lessons-clear-btn { padding: 6px 12px; border-radius: 8px; border: 1px solid var(--mc-border); background: var(--mc-bg-muted); color: var(--mc-text-secondary); cursor: pointer; font-size: 12px; }
.lessons-clear-btn:hover:not(:disabled) { background: var(--mc-danger-bg); color: var(--mc-danger); border-color: var(--mc-danger); }
.lessons-clear-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* Layer-1: drawer inline-edit blocks (Overview / Body) */
.detail-block { display: flex; flex-direction: column; gap: 10px; margin-bottom: 18px; }
.detail-block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
}
.detail-block-head.detail-subhead {
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid var(--mc-border-light);
}
.detail-block-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--mc-text-primary);
  letter-spacing: 0.02em;
  margin: 0;
}
.edit-actions { display: flex; gap: 6px; }
.detail-edit-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 11px;
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}
.detail-edit-btn:hover:not(:disabled) {
  background: var(--mc-primary-bg);
  border-color: var(--mc-primary);
  color: var(--mc-primary);
}
.detail-edit-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.detail-edit-btn.detail-edit-save {
  background: var(--mc-primary);
  border-color: var(--mc-primary);
  color: #fff;
}
.detail-edit-btn.detail-edit-save:hover:not(:disabled) {
  background: var(--mc-primary-hover);
  border-color: var(--mc-primary-hover);
  color: #fff;
}
.detail-edit-btn.detail-edit-cancel { color: var(--mc-text-secondary); }

.detail-readonly-banner {
  margin: 0;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--mc-bg-muted);
  border: 1px dashed var(--mc-border);
  font-size: 12px;
  color: var(--mc-text-secondary);
  line-height: 1.5;
}

.identity-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 16px;
  margin: 4px 0 0;
}
.identity-grid .kv { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.identity-grid .kv-full { grid-column: 1 / -1; }
.identity-grid dt {
  font-size: 11px;
  font-weight: 600;
  color: var(--mc-text-tertiary);
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
.identity-grid dd {
  margin: 0;
  font-size: 13px;
  color: var(--mc-text-primary);
  word-break: break-word;
  line-height: 1.5;
}
.identity-grid dd code {
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--mc-bg-sunken);
}

.form-grid-tight { gap: 10px 12px; }
.form-grid-tight .form-input,
.form-grid-tight .form-textarea { padding: 7px 10px; font-size: 13px; }

.detail-collapsible {
  margin-top: 12px;
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  background: var(--mc-bg-muted);
  padding: 0;
}
.detail-collapsible > summary {
  cursor: pointer;
  padding: 9px 12px;
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-secondary);
  list-style: none;
  user-select: none;
}
.detail-collapsible > summary::-webkit-details-marker { display: none; }
.detail-collapsible > summary::before {
  content: '▸';
  display: inline-block;
  margin-right: 6px;
  font-size: 10px;
  transition: transform 0.15s;
}
.detail-collapsible[open] > summary::before { transform: rotate(90deg); }
.detail-collapsible > summary:hover { color: var(--mc-text-primary); }
.detail-collapsible > *:not(summary) { padding: 0 12px 12px; }

/* Layer-1: slimmed New-Skill modal */
.modal.modal-slim { max-width: 480px; }
.modal-hint {
  font-size: 12px;
  color: var(--mc-text-secondary);
  margin: 0 0 14px;
  line-height: 1.5;
}
@media (max-width: 600px) {
  .identity-grid { grid-template-columns: 1fr; }
}

/* Icon row — preview tile + label + picker button. Used in the drawer
 * Display section and the create modal so both flows look identical. */
.identity-icon-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(123, 88, 67, 0.05);
  margin-bottom: 12px;
}
:global(html.dark .identity-icon-row) {
  background: rgba(255, 255, 255, 0.04);
}
.identity-icon-row--create {
  margin: 4px 0 16px;
}
.identity-icon-preview {
  background: rgba(255, 255, 255, 0.7);
  border-radius: 10px;
  padding: 4px;
  box-sizing: content-box;
  flex-shrink: 0;
}
:global(html.dark .identity-icon-preview) {
  background: rgba(255, 255, 255, 0.08);
}
.identity-icon-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}
.identity-icon-label {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--mc-text-tertiary);
}
.identity-icon-value {
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  color: var(--mc-text-primary);
  background: transparent;
  padding: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.identity-icon-empty {
  font-size: 12px;
  font-style: italic;
  color: var(--mc-text-tertiary);
}

/* ============================================================
 * MateClaw frosted-glass drawer
 * Mirrors Settings/Models/AddProviderDrawer.vue so the skill
 * detail surface lives in the same visual language as the rest
 * of the app — depth via translucency, not borders.
 * ============================================================ */
.mc-drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(20, 14, 10, 0.32);
  backdrop-filter: blur(8px) saturate(140%);
  -webkit-backdrop-filter: blur(8px) saturate(140%);
  z-index: 1500;
  display: flex;
  justify-content: flex-end;
}
:global(html.dark .mc-drawer-overlay) {
  background: rgba(0, 0, 0, 0.5);
}

.mc-drawer-panel {
  width: 640px;
  max-width: 92vw;
  height: 100%;
  background: rgba(255, 250, 245, 0.78);
  backdrop-filter: blur(48px) saturate(180%);
  -webkit-backdrop-filter: blur(48px) saturate(180%);
  border-left: 1px solid rgba(255, 255, 255, 0.4);
  box-shadow: -24px 0 60px rgba(25, 14, 8, 0.16);
  display: flex;
  flex-direction: column;
  animation: mc-drawer-slide 0.36s cubic-bezier(0.32, 0.72, 0, 1);
  transition: width 0.32s cubic-bezier(0.32, 0.72, 0, 1);
}
.mc-drawer-panel--wide {
  /* Editing SKILL.md needs room — 640 was cramped for code authoring. */
  width: 880px;
}
:global(html.dark .mc-drawer-panel) {
  background: rgba(32, 26, 22, 0.82);
  border-left-color: rgba(255, 255, 255, 0.10);
  box-shadow: -24px 0 60px rgba(0, 0, 0, 0.5);
}

@keyframes mc-drawer-slide {
  from { transform: translateX(100%); }
  to { transform: translateX(0); }
}

.mc-drawer-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 22px 26px 18px;
  border-bottom: 1px solid rgba(123, 88, 67, 0.10);
  flex-shrink: 0;
}
:global(html.dark .mc-drawer-header) {
  border-bottom-color: rgba(255, 255, 255, 0.06);
}
.mc-drawer-header__meta {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.mc-drawer-icon-shell {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  background: rgba(255, 255, 255, 0.55);
  box-shadow: inset 0 0 0 1px rgba(123, 88, 67, 0.10);
}
:global(html.dark .mc-drawer-icon-shell) {
  background: rgba(255, 255, 255, 0.06);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.08);
}
.mc-drawer-title {
  margin: 0 0 2px;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--mc-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.mc-drawer-subtitle {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--mc-text-tertiary);
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.mc-drawer-close {
  background: transparent;
  border: 0;
  padding: 8px;
  border-radius: 999px;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  flex-shrink: 0;
  transition: background 0.15s ease, color 0.15s ease;
}
.mc-drawer-close:hover {
  background: rgba(123, 88, 67, 0.08);
  color: var(--mc-text-primary);
}
:global(html.dark .mc-drawer-close:hover) {
  background: rgba(255, 255, 255, 0.08);
  color: var(--mc-text-primary);
}

.mc-drawer-content {
  flex: 1;
  overflow-y: auto;
  padding: 18px 22px 28px;
}
.mc-drawer-content--takeover {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 22px 22px;
  overflow: hidden;
}

/* Re-skin the original detail-tabs row for the new drawer surface —
 * pill row instead of bordered tabs, matches the cleaner pill look
 * already used elsewhere in the app. */
.mc-drawer-content .detail-tabs {
  border-bottom: none;
  padding-bottom: 0;
  gap: 2px;
  margin-bottom: 16px;
  background: rgba(123, 88, 67, 0.06);
  padding: 4px;
  border-radius: 999px;
  width: fit-content;
}
:global(html.dark .mc-drawer-content .detail-tabs) {
  background: rgba(255, 255, 255, 0.06);
}
.mc-drawer-content .detail-tab {
  padding: 6px 14px;
  border-radius: 999px;
  margin-bottom: 0;
  font-size: 12px;
}
.mc-drawer-content .detail-tab.active {
  background: rgba(255, 255, 255, 0.85);
  color: var(--mc-primary-hover);
  border-bottom: none;
  margin-bottom: 0;
  box-shadow: 0 1px 3px rgba(25, 14, 8, 0.08);
}
:global(html.dark .mc-drawer-content .detail-tab.active) {
  background: rgba(255, 255, 255, 0.14);
}

/* Section blocks become System-Settings style cards: one rounded
 * frosted card per logical group, hairline borders inside. */
.mc-drawer-content .detail-block {
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.55);
  padding: 14px 16px;
  margin-bottom: 14px;
  box-shadow: 0 1px 3px rgba(25, 14, 8, 0.04);
}
:global(html.dark .mc-drawer-content .detail-block) {
  background: rgba(255, 255, 255, 0.06);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
}
.mc-drawer-content .detail-block-head {
  margin-bottom: 8px;
}
.mc-drawer-content .detail-block-head.detail-subhead {
  border-top: 1px solid rgba(123, 88, 67, 0.08);
  margin-top: 12px;
  padding-top: 12px;
}
:global(html.dark .mc-drawer-content .detail-block-head.detail-subhead) {
  border-top-color: rgba(255, 255, 255, 0.06);
}
.mc-drawer-content .detail-readonly-banner {
  background: rgba(123, 88, 67, 0.06);
  border: none;
  border-radius: 10px;
  padding: 8px 12px;
}
:global(html.dark .mc-drawer-content .detail-readonly-banner) {
  background: rgba(255, 255, 255, 0.05);
}
.mc-drawer-content .detail-pre {
  background: rgba(123, 88, 67, 0.05);
  border: 1px solid rgba(123, 88, 67, 0.08);
  border-radius: 10px;
}
:global(html.dark .mc-drawer-content .detail-pre) {
  background: rgba(0, 0, 0, 0.25);
  border-color: rgba(255, 255, 255, 0.06);
}
.mc-drawer-content .detail-collapsible {
  background: rgba(123, 88, 67, 0.04);
  border: none;
}
:global(html.dark .mc-drawer-content .detail-collapsible) {
  background: rgba(255, 255, 255, 0.04);
}

/* Manifest-projected chips: read-only metadata facts row. */
.meta-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(123, 88, 67, 0.07);
  font-size: 12px;
  color: var(--mc-text-primary);
  font-weight: 500;
}
:global(html.dark .meta-chip) {
  background: rgba(255, 255, 255, 0.07);
}
.meta-chip code {
  background: transparent;
  padding: 0;
  font-size: 12px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  color: var(--mc-text-primary);
}
.meta-chip-label {
  font-size: 10px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--mc-text-tertiary);
}

/* Body-edit takeover: a single editor occupying the whole drawer. */
.takeover-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0 6px;
}
.takeover-toolbar--sub {
  border-top: 1px solid rgba(123, 88, 67, 0.08);
  padding-top: 14px;
  margin-top: 8px;
}
:global(html.dark .takeover-toolbar--sub) {
  border-top-color: rgba(255, 255, 255, 0.06);
}
.takeover-section {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--mc-text-tertiary);
}
.takeover-editor {
  flex: 1;
  min-height: 0;
  width: 100%;
  resize: none;
  border-radius: 12px;
  border: 1px solid rgba(123, 88, 67, 0.12);
  background: rgba(255, 255, 255, 0.7);
  padding: 14px 16px;
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.6;
  color: var(--mc-text-primary);
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.takeover-editor:focus {
  border-color: rgba(217, 119, 87, 0.45);
  box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.1);
}
.takeover-editor--secondary {
  flex: 0 0 38%;
}
:global(html.dark .takeover-editor) {
  background: rgba(0, 0, 0, 0.3);
  border-color: rgba(255, 255, 255, 0.08);
  color: var(--mc-text-primary);
}

/* Primary edit affordance — used by the "Edit SKILL.md" entry point
 * to signal it's the main authoring action, not just a tweak. */
.detail-edit-btn.detail-edit-primary {
  background: var(--mc-primary);
  border-color: var(--mc-primary);
  color: #fff;
}
.detail-edit-btn.detail-edit-primary:hover:not(:disabled) {
  background: var(--mc-primary-hover);
  border-color: var(--mc-primary-hover);
  color: #fff;
}

.mc-drawer-fade-enter-active,
.mc-drawer-fade-leave-active {
  transition: opacity 0.22s ease;
}
.mc-drawer-fade-enter-from,
.mc-drawer-fade-leave-to {
  opacity: 0;
}

/* Mobile: bottom sheet so the drawer doesn't crush 92vw. */
@media (max-width: 768px) {
  .mc-drawer-overlay {
    justify-content: stretch;
    align-items: flex-end;
  }
  .mc-drawer-panel,
  .mc-drawer-panel--wide {
    width: 100%;
    max-width: 100%;
    height: 92vh;
    border-left: 0;
    border-top: 1px solid rgba(255, 255, 255, 0.4);
    border-top-left-radius: 20px;
    border-top-right-radius: 20px;
    animation: mc-drawer-slide-up 0.32s cubic-bezier(0.32, 0.72, 0, 1);
  }
  :global(html.dark .mc-drawer-panel) {
    border-top-color: rgba(255, 255, 255, 0.08);
  }
  @keyframes mc-drawer-slide-up {
    from { transform: translateY(100%); }
    to { transform: translateY(0); }
  }
}
</style>
