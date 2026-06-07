<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner agents-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">{{ t('agents.kicker') }}</div>
            <h1 class="mc-page-title">{{ t('agents.title') }}</h1>
            <p class="mc-page-desc">{{ t('agents.desc') }}</p>
          </div>
          <div class="header-right">
            <!-- Roster / Live view switch — one team, two states. Admin only.
                 Sits on the header line, level with the New Employee button. -->
            <div v-if="isAdminRole" class="view-switch">
              <button
                class="view-seg"
                :class="{ 'is-active': view === 'roster' }"
                @click="setView('roster')"
              >{{ t('agents.views.roster') }}</button>
              <button
                class="view-seg"
                :class="{ 'is-active': view === 'live' }"
                @click="setView('live')"
              >
                <span v-if="liveRunning > 0" class="seg-pulse"></span>
                {{ t('agents.views.live') }}
                <span
                  v-if="liveRunning > 0"
                  class="seg-count"
                  :class="{ warn: liveStuck > 0 }"
                >{{ liveRunning }}</span>
              </button>
            </div>
            <button class="btn-primary" @click="openCreateModal">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
              </svg>
              {{ t('agents.newAgent') }}
            </button>
          </div>
        </div>

        <!-- Roster: the team -->
        <template v-if="view === 'roster'">
        <div class="agents-toolbar mc-surface-card">
          <div class="filter-bar">
            <div class="search-box">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
              </svg>
              <input v-model="searchText" :placeholder="t('agents.search')" class="search-input" />
            </div>
            <div class="filter-tabs">
              <button v-for="tab in filterTabs" :key="tab.value" class="filter-tab"
                :class="{ active: activeFilter === tab.value }" @click="activeFilter = tab.value">
                {{ t(tab.key) }}
              </button>
            </div>
          </div>
          <!-- Tag filter (#146): orthogonal to the type/status tabs; click to
               toggle, multi-select narrows by intersection (AND). -->
          <div v-if="availableTags.length" class="tag-filter-bar">
            <span class="tag-filter-bar__label">{{ t('agents.tagFilter.label') }}</span>
            <button v-for="tag in visibleTags" :key="tag" class="tag-filter-chip"
              :class="{ active: activeTags.includes(tag) }" @click="toggleTag(tag)">
              {{ tag }}
            </button>
            <input v-if="hasMoreTags" v-model="tagSearch" class="tag-filter-search"
              :placeholder="t('agents.tagFilter.search')" />
            <span v-if="tagSearch && !visibleTags.length" class="tag-filter-bar__empty">
              {{ t('agents.tagFilter.noMatch') }}
            </span>
            <button v-if="activeTags.length" class="tag-filter-clear" @click="clearTagFilter">
              {{ t('agents.tagFilter.clear') }}
            </button>
          </div>
        </div>

        <!-- Agent card grid -->
        <div class="agent-grid" v-if="filteredAgents.length > 0">
          <div
            v-for="agent in filteredAgents"
            :key="agent.id"
            class="agent-card mc-surface-card"
            :class="{ 'agent-card--disabled': !agent.enabled }"
          >
            <!--
              Employee-card layout: avatar, name, one-line tagline, primary
              chat action, and a hover-revealed overflow row. Anything that
              isn't identity-or-action lives behind the overflow row to keep
              the card readable at a glance.
            -->
            <div class="agent-card__top">
              <span
                class="agent-card__avatar"
                :class="{ 'agent-card__avatar--off': !agent.enabled }"
                :style="{ color: agentIconColor(agent.icon) }"
              >
                <SkillIcon :value="agent.icon" :size="40" :fallback="'🧑‍💼'" />
              </span>
              <div class="agent-card__identity">
                <h3 class="agent-card__name">{{ agent.name }}</h3>
                <p class="agent-card__tagline">
                  {{ agentTagline(agent) || t('agents.messages.noTagline') }}
                </p>
                <div v-if="agentTags(agent).length" class="agent-card__tags">
                  <span v-for="tag in agentTags(agent)" :key="tag" class="agent-card__tag"
                    :class="{ active: activeTags.includes(tag) }" @click="toggleTag(tag)">
                    {{ tag }}
                  </span>
                </div>
              </div>
            </div>

            <div class="agent-card__action-row">
              <button class="agent-card__primary" @click="goToChat(agent)">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
                </svg>
                {{ t('agents.actions.chat') }}
              </button>
              <div class="agent-card__overflow">
                <label class="toggle-switch toggle-switch--sm" :title="t('agents.fields.enabled')">
                  <input type="checkbox" :checked="agent.enabled" @change="toggleAgent(agent)" />
                  <span class="toggle-slider"></span>
                </label>
                <button class="action-btn" :title="t('agents.tabs.context')" @click="goToAgentContextFor(agent)">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>
                  </svg>
                </button>
                <button class="action-btn" :title="t('agents.actions.edit')" @click="openEditModal(agent)">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/>
                    <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>
                  </svg>
                </button>
                <button class="action-btn danger" :title="t('agents.actions.delete')" @click="deleteAgent(agent)">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <polyline points="3 6 5 6 21 6"/>
                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/>
                  </svg>
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- Empty state -->
        <div v-else class="empty-state mc-surface-card">
          <div class="empty-icon">🤖</div>
          <h3>{{ t('agents.emptyTitle') }}</h3>
          <p>{{ t('agents.emptyDesc') }}</p>
          <button class="btn-primary" @click="openCreateModal">{{ t('agents.newAgent') }}</button>
        </div>
        </template>

        <!-- Live: what the team is doing right now -->
        <LivePanel v-else />
      </div>
    </div>
    

    <!-- Template Selector Modal -->
    <div v-if="showTemplateSelector" class="modal-overlay">
      <div class="modal template-modal">
        <div class="modal-header">
          <h2>{{ t('agents.templates.title') }}</h2>
          <button class="modal-close" @click="showTemplateSelector = false">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <p class="template-desc">{{ t('agents.templates.desc') }}</p>
          <div class="template-grid">
            <div
              v-for="tpl in templates"
              :key="tpl.id"
              class="template-card mc-surface-card"
              :class="{ applying: applyingTemplate }"
              @click="!applyingTemplate && applyTemplate(tpl.id)"
            >
              <div class="template-icon" :style="{ color: agentIconColor(tpl.icon) }">
                <SkillIcon :value="tpl.icon" :size="28" :fallback="'🧑‍💼'" />
              </div>
              <div class="template-info">
                <h4 class="template-name">{{ $i18n.locale === 'zh-CN' && tpl.nameZh ? tpl.nameZh : tpl.name }}</h4>
                <p class="template-detail">{{ $i18n.locale === 'zh-CN' && tpl.descriptionZh ? tpl.descriptionZh : tpl.description }}</p>
              </div>
              <div class="template-tags">
                <span v-for="tag in (tpl.tags || '').split(',').filter(Boolean)" :key="tag" class="tag-chip">{{ tag.trim() }}</span>
              </div>
            </div>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="openBlankCreateModal">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            {{ t('agents.templates.skip') }}
          </button>
        </div>
      </div>
    </div>

    <!-- Shared icon picker — opened from the Basic tab's [Pick icon] button. -->
    <SkillIconPicker
      v-model:visible="iconPickerVisible"
      :model-value="form.icon"
      @apply="(v: string) => (form.icon = v)"
    />

    <!-- Create/Edit Modal -->
    <div v-if="showModal" class="modal-overlay">
      <div class="modal">
        <div class="modal-header">
          <h2>{{ editingAgent ? t('agents.modal.editTitle') : t('agents.modal.newTitle') }}</h2>
          <button class="modal-close" @click="closeModal">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <div class="modal-body">
          <!-- Tab Bar -->
          <div class="modal-tabs">
            <button class="modal-tab" :class="{ active: modalTab === 'basic' }" @click="modalTab = 'basic'">
              {{ t('agents.tabs.basic', 'Basic') }}
            </button>
            <button v-if="editingAgent" class="modal-tab" :class="{ active: modalTab === 'skills' }" @click="modalTab = 'skills'">
              {{ t('agents.tabs.skills', 'Skills') }}
              <!-- Issue #184: when the disable flag is on, suppress the
                   stale-pick count badge and show an "off" state instead —
                   the count would otherwise contradict the disable toggle
                   visible in the tab content. -->
              <span v-if="form.skillsDisabled" class="tab-badge tab-badge--off">{{ t('agents.binding.disableAllSkillsBadge') }}</span>
              <span v-else-if="selectedSkillIds.length" class="tab-badge">{{ selectedSkillIds.length }}</span>
            </button>
            <button v-if="editingAgent" class="modal-tab" :class="{ active: modalTab === 'tools' }" @click="modalTab = 'tools'">
              {{ t('agents.tabs.tools', 'Tools') }}
              <span v-if="form.toolsDisabled" class="tab-badge tab-badge--off">{{ t('agents.binding.disableAllToolsBadge') }}</span>
              <span v-else-if="selectedToolNames.length" class="tab-badge">{{ selectedToolNames.length }}</span>
            </button>
            <button v-if="editingAgent" class="modal-tab" :class="{ active: modalTab === 'providers' }" @click="modalTab = 'providers'">
              {{ t('agents.tabs.providers', 'Providers') }}
              <span v-if="selectedProviderIds.length" class="tab-badge">{{ selectedProviderIds.length }}</span>
            </button>
            <button v-if="editingAgent" class="modal-tab" :class="{ active: modalTab === 'wiki' }" @click="modalTab = 'wiki'">
              {{ t('agents.tabs.wiki', 'Wiki') }}
              <span v-if="selectedKbIds.length" class="tab-badge">{{ selectedKbIds.length }}</span>
            </button>
          </div>

          <!-- Basic Tab -->
          <div v-if="modalTab === 'basic'" class="form-grid">
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.name') }} *</label>
              <input v-model="form.name" class="form-input" :placeholder="t('agents.placeholders.name')" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.icon') }}</label>
              <button type="button" class="icon-picker-trigger" @click="iconPickerVisible = true">
                <SkillIcon :value="form.icon" :size="24" :fallback="'🤖'" />
                <span class="icon-picker-trigger__label">
                  {{ form.icon || t('common.iconPicker.none') }}
                </span>
                <span class="icon-picker-trigger__action">{{ t('common.iconPicker.pickerOpen') }}</span>
              </button>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.type') }}</label>
              <select v-model="form.agentType" class="form-input">
                <option value="react">{{ t('agents.types.react') }}</option>
                <option value="plan_execute">{{ t('agents.types.planExecute') }}</option>
              </select>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.maxIterations') }}</label>
              <input v-model.number="form.maxIterations" type="number" min="1" max="50" class="form-input" />
            </div>
            <!-- RFC-03 Lane G1: per-Agent model override. Empty value falls
                 back to the global default in ModelConfigService.resolveModel. -->
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.modelName') }}</label>
              <select v-model="form.modelName" class="form-input">
                <option value="">{{ t('agents.fields.modelGlobalDefault') }}</option>
                <option v-for="m in availableModels" :key="m.id" :value="m.modelName">
                  {{ m.name }} ({{ m.provider }}/{{ m.modelName }})
                </option>
              </select>
              <p class="form-hint">{{ t('agents.fields.modelHint') }}</p>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.defaultThinkingLevel') }}</label>
              <select v-model="form.defaultThinkingLevel" class="form-input">
                <option :value="null">{{ t('agents.thinkingLevels.auto') }}</option>
                <option value="off">{{ t('agents.thinkingLevels.off') }}</option>
                <option value="low">{{ t('agents.thinkingLevels.low') }}</option>
                <option value="medium">{{ t('agents.thinkingLevels.medium') }}</option>
                <option value="high">{{ t('agents.thinkingLevels.high') }}</option>
                <option value="max">{{ t('agents.thinkingLevels.max') }}</option>
              </select>
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('agents.fields.workspaceBasePath') }}</label>
              <input v-model="form.workspaceBasePath" class="form-input" :placeholder="t('agents.placeholders.workspaceBasePath')" />
              <p class="form-hint">{{ t('agents.fields.workspaceBasePathHint') }}</p>
            </div>
            <!--
              Identity triad: role + goal + backstory map to H2 sections in
              the stored systemPrompt. The card tagline is derived from
              role + goal, so the Goal field shows a live preview to make
              the constraint visible while typing.
            -->
            <div class="form-group full-width">
              <label class="form-label">{{ t('agents.fields.role') }}</label>
              <input v-model="profileForm.role" class="form-input" :placeholder="t('agents.placeholders.role')" />
              <p class="form-hint">{{ t('agents.fields.roleHint') }}</p>
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('agents.fields.goal') }}</label>
              <input v-model="profileForm.goal" class="form-input" :placeholder="t('agents.placeholders.goal')" />
              <p class="form-hint" :class="{ 'form-hint--warn': taglinePreviewWidth > taglineSoftLimit }">
                <span class="form-hint__label">{{ t('agents.fields.taglinePreview') }}</span>
                <span class="form-hint__value">{{ taglinePreview || t('agents.messages.noTagline') }}</span>
                <span class="form-hint__counter">{{ taglinePreviewWidth }}/{{ taglineSoftLimit }}</span>
              </p>
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('agents.fields.backstory') }}</label>
              <textarea v-model="profileForm.backstory" class="form-textarea" rows="3" :placeholder="t('agents.placeholders.backstory')"></textarea>
              <p class="form-hint">{{ t('agents.fields.backstoryHint') }}</p>
            </div>
            <div class="form-group full-width">
              <label class="form-label">{{ t('agents.fields.description') }}</label>
              <input v-model="form.description" class="form-input" :placeholder="t('agents.placeholders.description')" />
            </div>
            <div class="form-group full-width">
              <details class="advanced-prompt">
                <summary class="advanced-prompt__summary">
                  {{ t('agents.fields.extraInstructions') }}
                </summary>
                <textarea v-model="profileForm.extra" class="form-textarea" rows="4" :placeholder="t('agents.placeholders.extraInstructions')"></textarea>
                <p class="form-hint">{{ t('agents.fields.extraInstructionsHint') }}</p>
              </details>
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.tags') }}</label>
              <input v-model="form.tags" class="form-input" :placeholder="t('agents.placeholders.tags')" />
            </div>
            <div class="form-group">
              <label class="form-label">{{ t('agents.fields.enabled') }}</label>
              <label class="toggle-switch" style="margin-top: 6px;">
                <input type="checkbox" v-model="form.enabled" />
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>

          <!-- Skills Tab -->
          <div v-if="modalTab === 'skills'" class="binding-tab">
            <div class="binding-intro">
              <span class="binding-intro__kicker">{{ t('agents.binding.skillsKicker') }}</span>
              <p class="binding-intro__tagline">{{ t('agents.binding.skillsTagline') }}</p>
            </div>
            <!-- Issue #184: explicit "no skills" toggle. Empty selection
                 alone falls back to "inherit global default" (legacy
                 contract), so users who want zero skills in the context
                 need this dedicated bit. -->
            <div class="binding-disable-row">
              <label class="binding-disable-label">
                <input type="checkbox" v-model="form.skillsDisabled" class="binding-disable-checkbox" />
                <span class="binding-disable-text">
                  <strong>{{ t('agents.binding.disableAllSkills') }}</strong>
                  <span class="binding-disable-hint">{{ t('agents.binding.disableAllSkillsHint') }}</span>
                </span>
              </label>
            </div>
            <p class="binding-hint">{{ t('agents.binding.skillsHint') }}</p>
            <div v-if="availableSkills.length === 0" class="binding-empty">{{ t('agents.binding.noSkills') }}</div>
            <template v-else>
              <div class="binding-search" :class="{ 'binding-search--disabled': form.skillsDisabled }">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
                </svg>
                <input v-model="skillBindingSearch" :placeholder="t('agents.binding.searchSkills')" :disabled="form.skillsDisabled" />
              </div>
              <div v-if="filteredAvailableSkills.length === 0" class="binding-empty binding-empty--compact">{{ t('agents.binding.noMatchingSkills') }}</div>
              <div v-else class="binding-list" :class="{ 'binding-list--disabled': form.skillsDisabled }">
                <label
                  v-for="skill in filteredAvailableSkills"
                  :key="skill.id"
                  class="binding-item"
                  :class="{
                    selected: !form.skillsDisabled && selectedSkillIds.includes(skill.id),
                    'binding-item--inert': form.skillsDisabled,
                  }"
                >
                  <!-- Manual :checked instead of v-model: when skillsDisabled
                       is on, the stale picks still live in selectedSkillIds
                       (we keep them so flipping the toggle back off restores
                       the previous selection in one click). Driving the
                       checkbox from a derived expression lets us hide the
                       stale checked state from the user without mutating
                       the underlying array. The save path already clears
                       the array on send when the flag is on. -->
                  <input
                    type="checkbox"
                    :value="skill.id"
                    :checked="!form.skillsDisabled && selectedSkillIds.includes(skill.id)"
                    @change="onSkillToggle(skill.id, $event)"
                    class="binding-checkbox"
                    :disabled="form.skillsDisabled"
                  />
                  <span class="binding-icon"><SkillIcon :value="skill.icon" :size="20" :fallback="'🧩'" /></span>
                  <div class="binding-info">
                    <span class="binding-name">{{ resolveSkillName(skill) }}</span>
                    <span v-if="skill.description" class="binding-desc">{{ skill.description?.slice(0, 80) }}</span>
                  </div>
                  <span v-if="skill.version" class="binding-version">v{{ skill.version }}</span>
                </label>
              </div>
            </template>
          </div>

          <!-- Tools Tab — RFC-090 §9.2 调整 B: Advanced bypass for atomic
               tools not packaged as skills (e.g. datetime, delegate_agent).
               Skill bindings already auto-expand allowed-tools (§14.2), so
               the picker is collapsed by default to reduce noise. -->
          <div v-if="modalTab === 'tools'" class="binding-tab">
            <div class="binding-intro">
              <span class="binding-intro__kicker">{{ t('agents.binding.toolsKicker') }}</span>
              <p class="binding-intro__tagline">{{ t('agents.binding.toolsTagline') }}</p>
            </div>
            <!-- Issue #184 mirror of the skills tab: explicit opt-out so the
                 LLM advertises zero user-pickable tools (system-level
                 memory primitives still pass — see backend SYSTEM_LEVEL_TOOLS). -->
            <div class="binding-disable-row">
              <label class="binding-disable-label">
                <input type="checkbox" v-model="form.toolsDisabled" class="binding-disable-checkbox" />
                <span class="binding-disable-text">
                  <strong>{{ t('agents.binding.disableAllTools') }}</strong>
                  <span class="binding-disable-hint">{{ t('agents.binding.disableAllToolsHint') }}</span>
                </span>
              </label>
            </div>
            <!-- Issue #184: when the disable flag is on, treat the count as
                 zero for visual affordances — auto-open / count badge / chevron
                 should all behave as if there are no picks, matching the
                 "saving clears bindings" contract. The underlying array is
                 left intact so toggling the flag back off restores them. -->
            <details class="advanced-tools" :open="(!form.toolsDisabled && selectedToolNames.length > 0) || advancedToolsOpen">
              <summary class="advanced-tools-summary" @click.prevent="advancedToolsOpen = !advancedToolsOpen">
                <span class="advanced-tools-title">
                  {{ t('agents.binding.advancedToolsTitle') }}
                  <span v-if="!form.toolsDisabled && selectedToolNames.length > 0" class="advanced-tools-count">{{ selectedToolNames.length }}</span>
                </span>
                <span class="advanced-tools-chevron">{{ (advancedToolsOpen || (!form.toolsDisabled && selectedToolNames.length > 0)) ? '▾' : '▸' }}</span>
              </summary>
              <p class="binding-hint">{{ t('agents.binding.toolsHint') }}</p>
              <p class="binding-hint advanced-tools-note">{{ t('agents.binding.advancedToolsHint') }}</p>
              <p class="binding-hint advanced-tools-note">{{ t('agents.binding.toolUnionHint') }}</p>
              <div v-if="availableToolGroups.length > 0" class="binding-search">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>
                </svg>
                <input v-model="toolBindingSearch" :placeholder="t('agents.binding.searchTools')" />
              </div>
              <!-- Render the empty state only when there is genuinely
                   nothing to show. availableTools can be empty while
                   availableToolGroups still contains a synthesized
                   orphan group (saved bindings whose tools dropped out
                   of the catalog) — that case must reach the list so
                   the user can clean those orphans up. -->
              <div v-if="availableToolGroups.length === 0" class="binding-empty">{{ t('agents.binding.noTools') }}</div>
              <div v-else-if="filteredAvailableToolGroups.length === 0" class="binding-empty binding-empty--compact">{{ t('agents.binding.noMatchingTools') }}</div>
              <div v-else class="binding-list">
                <template v-for="group in filteredAvailableToolGroups" :key="group.groupId">
                  <div class="binding-group-header">
                    <span>{{ group.label }}</span>
                    <!-- MCP group badge reflects the agent's MCP scope:
                         with no MCP tool ticked, every enabled MCP tool is
                         available by default; once any MCP tool is ticked,
                         the agent is restricted to the ticked set. To deny
                         an MCP tool when none are ticked, users still have
                         Security → Tool Guard. -->
                    <span
                      v-if="group.groupId && group.groupId.startsWith('mcp:')"
                      class="binding-group-note"
                      :title="anyMcpToolSelected
                        ? t('agents.binding.mcpScopedTooltip')
                        : t('agents.binding.mcpAutoIncludedTooltip')"
                    >
                      {{ anyMcpToolSelected
                        ? t('agents.binding.mcpScopedBadge')
                        : t('agents.binding.mcpAutoIncludedBadge') }}
                    </span>
                  </div>
                  <label
                    v-for="tool in group.tools"
                    :key="tool.rowId || `${group.groupId}#${tool.rawName}#${tool.name}`"
                    class="binding-item"
                    :class="{
                      selected: !form.toolsDisabled && tool._isSelected,
                      'binding-item--stale': tool.stale,
                      'binding-item--unavailable': !tool.available,
                      'binding-item--inert': form.toolsDisabled,
                    }"
                    :title="!tool.available
                      ? t('agents.binding.toolUnavailableTooltip', { reason: tool.unavailableReason || '' })
                      : (tool.stale ? t('agents.binding.toolStaleTooltip') : tool.name)"
                  >
                    <!-- Manual checkbox state. Two rows can share the
                         same tool.name (hash-collision twin or
                         duplicate-raw twin); v-model would auto-sync
                         both, so we drive each row's checked flag from
                         the pre-computed _isSelected derived in
                         availableToolGroups, which considers whether
                         this row's name is owned by a bindable twin.
                         Issue #184: gate visual checked-state on the
                         disable flag so stale picks don't show through
                         when "this agent uses no user-pickable tools"
                         is on. selectedToolNames is preserved so flipping
                         the toggle back off restores the prior selection. -->
                    <input
                      type="checkbox"
                      class="binding-checkbox"
                      :checked="!form.toolsDisabled && tool._isSelected"
                      :disabled="tool._isDisabled || form.toolsDisabled"
                      @change="onToolToggle(tool.name, $event)"
                    />
                    <span class="binding-icon">
                      <SkillIcon :value="tool.source === 'mcp' ? '🔌' : '🔧'" :size="20" :fallback="'🔧'" />
                    </span>
                    <div class="binding-info">
                      <span class="binding-name">{{ tool.rawName || tool.name }}</span>
                      <span v-if="tool.description" class="binding-desc">{{ tool.description?.slice(0, 80) }}</span>
                    </div>
                    <span v-if="tool.stale" class="binding-stale-badge">{{ t('agents.binding.toolStaleBadge') }}</span>
                    <span v-if="!tool.available" class="binding-unavailable-badge">{{ t('agents.binding.toolUnavailableBadge') }}</span>
                    <span v-else class="binding-type-badge">{{ tool.source }}</span>
                  </label>
                </template>
              </div>
            </details>
          </div>

          <!-- Providers Tab (RFC-009 PR-3) -->
          <div v-if="modalTab === 'providers'" class="binding-tab">
            <p class="binding-hint">{{ t('agents.binding.providersHint') }}</p>
            <!-- Picked: ordered list with up/down/remove controls -->
            <div v-if="selectedProviderIds.length" class="provider-pref-list">
              <div
                v-for="(pid, idx) in selectedProviderIds"
                :key="pid"
                class="provider-pref-item"
              >
                <span class="provider-pref-rank">{{ idx + 1 }}</span>
                <span class="provider-pref-name">{{ providerNameById(pid) }}</span>
                <span class="provider-pref-id">{{ pid }}</span>
                <button class="provider-pref-btn" :disabled="idx === 0" @click="moveProvider(idx, -1)">↑</button>
                <button class="provider-pref-btn" :disabled="idx === selectedProviderIds.length - 1" @click="moveProvider(idx, 1)">↓</button>
                <button class="provider-pref-btn danger" @click="removeProvider(idx)">✕</button>
              </div>
            </div>
            <div v-else class="binding-empty">{{ t('agents.binding.noProviderPreferences') }}</div>

            <!-- Unpicked: click to append -->
            <div v-if="unpickedProviders.length" class="provider-pref-pool">
              <p class="binding-hint" style="margin-top: 14px">{{ t('agents.binding.providersAddHint') }}</p>
              <button
                v-for="p in unpickedProviders"
                :key="p.id"
                class="provider-pref-add-btn"
                @click="addProvider(p.id)"
              >+ {{ p.name }}</button>
            </div>
          </div>

          <!-- Wiki / Knowledge Base Tab -->
          <div v-if="modalTab === 'wiki'" class="binding-tab">
            <div class="binding-intro">
              <span class="binding-intro__kicker">{{ t('agents.binding.wikiKicker') }}</span>
              <p class="binding-intro__tagline">{{ t('agents.binding.wikiTagline') }}</p>
            </div>
            <p class="binding-hint">{{ t('agents.binding.wikiHint') }}</p>
            <div v-if="availableKBs.length === 0" class="binding-empty">{{ t('agents.binding.noKBs') }}</div>
            <template v-else>
              <p class="binding-hint" :class="{ 'binding-hint--warn': selectedKbIds.length > 0 }">
                {{ selectedKbIds.length === 0 ? t('agents.binding.wikiScopeAll') : t('agents.binding.wikiScopeLimited', { count: selectedKbIds.length }) }}
              </p>
              <div class="binding-list">
                <label
                  v-for="kb in availableKBs"
                  :key="kb.id"
                  class="binding-item"
                  :class="{ selected: isKbInScope(kb.id) }"
                >
                  <input
                    type="checkbox"
                    class="binding-checkbox"
                    :checked="isKbInScope(kb.id)"
                    @change="toggleKbScope(kb.id)"
                  />
                  <span class="binding-icon">📚</span>
                  <div class="binding-info">
                    <span class="binding-name">{{ kb.name }}</span>
                    <span v-if="kb.description" class="binding-desc">{{ kb.description?.slice(0, 80) }}</span>
                  </div>
                  <!-- Primary toggle: only meaningful for in-scope KBs. -->
                  <button
                    type="button"
                    class="kb-primary-toggle"
                    :class="{ 'kb-primary-toggle--active': selectedKBId === String(kb.id) }"
                    :title="t('agents.binding.wikiSetPrimary')"
                    @click.prevent.stop="setPrimaryKb(kb.id)"
                  >{{ selectedKBId === String(kb.id) ? t('agents.binding.wikiPrimary') : t('agents.binding.wikiSetPrimary') }}</button>
                  <!-- binding-version class reused for pageCount badge (same positioning as skill version) -->
                  <span v-if="kb.pageCount != null" class="binding-version">{{ t('agents.binding.wikiPages', { count: kb.pageCount }, `${kb.pageCount} pages`) }}</span>
                </label>
              </div>
            </template>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="closeModal">{{ t('common.cancel') }}</button>
          <button class="btn-primary" @click="saveAgent" :disabled="!form.name">
            {{ editingAgent ? t('agents.actions.update') : t('agents.actions.create') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { mcToast } from '@/composables/useMcToast'
import { mcConfirm } from '@/components/common/useConfirm'
import { agentApi, agentBindingApi, modelApi, skillApi, toolApi, templateApi, liveApi, wikiApi } from '@/api/index'
import type { Agent } from '@/types/index'
import SkillIcon from '@/components/common/SkillIcon.vue'
import SkillIconPicker from '@/components/common/SkillIconPicker.vue'
import LivePanel from '@/components/live/LivePanel.vue'
import {
  emptyProfile,
  parsePrompt,
  serializePrompt,
  deriveTagline,
  taglineVisualWidth,
  TAGLINE_CJK_BUDGET,
  type AgentPromptProfile,
} from '@/utils/agentPromptProfile'
import { agentIconColor } from '@/utils/agentIconColor'
import { filterAgentBindingItems, filterAgentToolGroups } from '@/utils/agentBindingSearch'
import { useSkillName } from '@/composables/useSkillName'

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const { resolveSkillName } = useSkillName()
const agents = ref<Agent[]>([])
const searchText = ref('')
const activeFilter = ref('all')
// Tag filter (#146) — orthogonal to activeFilter; multi-select with AND
// (intersection) semantics. Empty array = no tag constraint.
const activeTags = ref<string[]>([])
const showModal = ref(false)
const editingAgent = ref<Agent | null>(null)
const modalTab = ref<'basic' | 'skills' | 'tools' | 'providers' | 'wiki'>('basic')
/** RFC-090 §9.2 调整 B — Tool picker is an Advanced bypass; collapsed by
 *  default but stays open as soon as the agent has any direct tool
 *  bindings, so existing users don't lose visibility on their picks. */
const advancedToolsOpen = ref(false)

// Binding state
const availableSkills = ref<any[]>([])
const availableTools = ref<any[]>([])
const skillBindingSearch = ref('')
const toolBindingSearch = ref('')

const filteredAvailableSkills = computed(() => filterAgentBindingItems(availableSkills.value, skillBindingSearch.value))

/**
 * Group the flat /tools/available payload by source so the picker
 * renders one section per origin (built-in, MCP per server). Groups
 * are stable in insertion order — built-in first because the API
 * returns them first, then MCP groups in server discovery order.
 *
 * <p>For each row we also pre-compute {@code _isSelected} /
 * {@code _isDisabled} so the template doesn't have to derive them from
 * {@code tool.name} alone. With hash-collision and duplicate-raw rows
 * sharing the same {@code name} as the bindable twin, naively using
 * {@code selectedToolNames.includes(tool.name)} would mark both checked
 * and let the user uncheck the unavailable one — the unchecked twin
 * would silently mutate the bound name. The pre-computed flags decouple
 * each row's UI state from any sibling row that shares its prefixed
 * name.
 */
const availableToolGroups = computed(() => {
  const groups: Record<string, { groupId: string; label: string; tools: any[] }> = {}
  const order: string[] = []

  // Names that any available row claims. Unavailable rows whose name is
  // also held by an available row are "shadowed" — they must never look
  // selected and must never accept a click. Unavailable rows whose name
  // ISN'T in this set are orphans (e.g. a saved binding whose tool got
  // removed upstream); the user must still be able to uncheck them.
  const bindableNames = new Set<string>()
  // Names that appear anywhere in availableTools (with either flag). Any
  // entry in selectedToolNames whose name is not in this set is a
  // "catalog-orphan" — saved before the upstream catalog dropped it —
  // and needs a synthesized row so the user can uncheck it.
  const knownNames = new Set<string>()
  for (const t of availableTools.value) {
    knownNames.add(t.name)
    if (t.available) bindableNames.add(t.name)
  }

  for (const t of availableTools.value) {
    const key = t.groupId || (t.source === 'mcp' ? `mcp:${t.providerId}` : 'builtin')
    if (!groups[key]) {
      const label = t.group || (t.source === 'mcp' ? `MCP · ${t.providerName ?? ''}` : t.source || 'tools')
      groups[key] = { groupId: key, label, tools: [] }
      order.push(key)
    }

    const inSelection = selectedToolNames.value.includes(t.name)
    const isOrphanUnavailable = !t.available && !bindableNames.has(t.name)
    // selected: only the bindable row owns the name; orphan unavailable
    // rows reflect their own selection state so the user can clean them up.
    const _isSelected = (t.available || isOrphanUnavailable) && inSelection
    // disabled: shadowed rows are hard-disabled (let the bindable twin
    // own the click); orphan unavailable rows allow only "uncheck"
    // (currently-selected → enabled; not selected → disabled).
    const _isDisabled = !t.available && !(isOrphanUnavailable && inSelection)
    groups[key].tools.push({ ...t, _isSelected, _isDisabled, _isOrphanUnavailable: isOrphanUnavailable })
  }

  // Catalog-orphan synthesis: any name in the existing binding that
  // /tools/available no longer returns at all. The backend save path
  // permits removing such names ("keeps" don't validate), but without a
  // visible row the user has no way to trigger the removal. Render them
  // in their own group; uncheck removes them from selectedToolNames and
  // the synthesized row vanishes on the next computed pass (the name is
  // no longer in selectedToolNames).
  const orphanNames = selectedToolNames.value.filter((n) => !knownNames.has(n))
  if (orphanNames.length > 0) {
    const orphanGroupId = 'orphan'
    groups[orphanGroupId] = {
      groupId: orphanGroupId,
      label: t('agents.binding.toolOrphanGroup'),
      tools: orphanNames.map((n) => ({
        rowId: `orphan#${n}`,
        source: 'orphan',
        providerId: null,
        providerName: null,
        name: n,
        rawName: n,
        description: t('agents.binding.toolOrphanDescription'),
        group: t('agents.binding.toolOrphanGroup'),
        groupId: orphanGroupId,
        stale: false,
        available: false,
        unavailableReason: 'NOT_IN_CATALOG',
        // Always selected (it is, by definition, in selectedToolNames)
        // and always uncheckable so the user can remove it.
        _isSelected: true,
        _isDisabled: false,
        _isOrphanUnavailable: true,
      })),
    }
    order.push(orphanGroupId)
  }
  return order.map((k) => groups[k])
})

const filteredAvailableToolGroups = computed(() => filterAgentToolGroups(availableToolGroups.value, toolBindingSearch.value))

/**
 * True when at least one ticked tool is an MCP tool. The backend reads
 * this as a deliberate per-agent MCP scope: only ticked MCP tools stay
 * in the effective allowlist. When false, every enabled MCP tool is
 * auto-included instead. Drives the MCP group badge in the picker.
 */
const anyMcpToolSelected = computed(() =>
  availableTools.value.some((tool) => tool.source === 'mcp' && selectedToolNames.value.includes(tool.name)),
)

/**
 * Manual checkbox handler — replaces v-model on the picker row so that
 * two rows sharing a tool name (collision/duplicate twins) don't drag
 * each other's selection state via Vue's v-model auto-sync.
 */
function onToolToggle(toolName: string, event: Event) {
  const target = event.target as HTMLInputElement
  if (target.checked) {
    if (!selectedToolNames.value.includes(toolName)) {
      selectedToolNames.value.push(toolName)
    }
  } else {
    selectedToolNames.value = selectedToolNames.value.filter((n) => n !== toolName)
  }
}

/**
 * Skill checkbox handler. Issue #184 — the row is driven by an explicit
 * {@code :checked} expression instead of {@code v-model} so the visual
 * checked state can be suppressed when {@code skillsDisabled} is on
 * without dropping the picks from {@code selectedSkillIds}. Toggling
 * the disable flag back off restores the prior selection in one click.
 */
function onSkillToggle(skillId: number | string, event: Event) {
  const target = event.target as HTMLInputElement
  if (target.checked) {
    if (!selectedSkillIds.value.includes(skillId as number)) {
      selectedSkillIds.value.push(skillId as number)
    }
  } else {
    selectedSkillIds.value = selectedSkillIds.value.filter((id) => id !== skillId)
  }
}
const selectedSkillIds = ref<number[]>([])
const selectedToolNames = ref<string[]>([])
// Agent wiki KB binding. `selectedKbIds` is the access scope: when non-empty
// the agent can only reach those KBs; empty = unrestricted (every KB in the
// workspace). `selectedKBId` is the default/primary KB among the scope, used
// by wiki tools when no kbId/kbName is given. IDs are strings (Snowflake).
const availableKBs = ref<any[]>([])
const selectedKBId = ref<string | null>(null)
const selectedKbIds = ref<string[]>([])

function isKbInScope(id: string | number): boolean {
  return selectedKbIds.value.includes(String(id))
}

/** Toggle a KB in/out of the access scope, keeping the primary consistent. */
function toggleKbScope(id: string | number) {
  const sid = String(id)
  const idx = selectedKbIds.value.indexOf(sid)
  if (idx >= 0) {
    selectedKbIds.value.splice(idx, 1)
    // Dropping the primary out of scope: fall back to another scoped KB.
    if (selectedKBId.value === sid) {
      selectedKBId.value = selectedKbIds.value[0] ?? null
    }
  } else {
    selectedKbIds.value.push(sid)
    // First KB added becomes the default primary automatically.
    if (selectedKBId.value === null) {
      selectedKBId.value = sid
    }
  }
}

/** Mark a KB as the default/primary; auto-adds it to the scope if needed. */
function setPrimaryKb(id: string | number) {
  const sid = String(id)
  if (!selectedKbIds.value.includes(sid)) {
    selectedKbIds.value.push(sid)
  }
  selectedKBId.value = sid
}
// RFC-009 PR-3: per-agent provider preference order
const availableProviders = ref<{ id: string; name: string }[]>([])
const selectedProviderIds = ref<string[]>([])
// RFC-03 Lane G1: per-Agent model override picker — populated from the
// global enabled-models list, blank value means "fall back to default".
const availableModels = ref<Array<{ id: number; name: string; provider: string; modelName: string }>>([])

// Template selector state
const showTemplateSelector = ref(false)
const templates = ref<any[]>([])
const applyingTemplate = ref(false)

const filterTabs = [
  { key: 'agents.tabs.all', value: 'all' },
  { key: 'agents.tabs.react', value: 'react' },
  { key: 'agents.tabs.planExecute', value: 'plan_execute' },
  { key: 'agents.tabs.enabled', value: 'enabled' },
  { key: 'agents.tabs.disabled', value: 'disabled' },
]

const defaultForm = (): Partial<Agent> & { name: string; defaultThinkingLevel: string | null } => ({
  name: '',
  description: '',
  agentType: 'react',
  systemPrompt: '',
  modelName: '', // RFC-03 G1 — empty means "use global default"
  maxIterations: 10,
  // Empty so SkillIcon's fallback (🤖) shows instead of pinning a literal
  // emoji into form.icon — otherwise the picker thinks the user picked
  // the robot emoji explicitly and reopens on the Emoji tab.
  icon: '',
  tags: '',
  enabled: true,
  defaultThinkingLevel: null,
  // Agent type declares this as `string | undefined`; using `undefined` keeps
  // the Partial<Agent> shape happy without widening the type to allow null.
  workspaceBasePath: undefined,
  primaryKbId: null,
  // Issue #184 — explicit opt-out flags. Default false matches the legacy
  // "zero rows = inherit global default" contract for newly-created agents.
  skillsDisabled: false,
  toolsDisabled: false,
})

const form = ref(defaultForm())
const iconPickerVisible = ref(false)

// Identity-triad fields displayed in the basic tab. Kept separate from
// `form.systemPrompt` so the textarea state stays predictable while the
// user types — we only flatten back to a single prompt at save time.
const profileForm = ref<AgentPromptProfile>(emptyProfile())
const taglineSoftLimit = TAGLINE_CJK_BUDGET

const taglinePreview = computed(() => deriveTagline(profileForm.value, form.value.description))
const taglinePreviewWidth = computed(() => taglineVisualWidth(taglinePreview.value))

/** Tagline shown on each agent card — derived from the stored systemPrompt
 *  and (as a fallback) the agent's description. Pure function, safe to call
 *  in the template. */
function agentTagline(agent: Agent): string {
  const profile = parsePrompt(agent.systemPrompt)
  return deriveTagline(profile, agent.description)
}

/** Parse an agent's comma-separated tags into a trimmed, non-empty, de-duped array. */
function agentTags(agent: Agent): string[] {
  const parsed = (agent.tags || '').split(',').map(t => t.trim()).filter(Boolean)
  return [...new Set(parsed)]
}

// Distinct tags across all agents, ordered by frequency (most-used first) so
// the filter bar surfaces the common categories. Hidden entirely when empty.
const availableTags = computed(() => {
  const freq = new Map<string, number>()
  for (const a of agents.value) {
    for (const tag of agentTags(a)) freq.set(tag, (freq.get(tag) ?? 0) + 1)
  }
  return [...freq.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).map(e => e[0])
})

// Cap the inline chips so a workspace with dozens of tags doesn't bury the
// roster under a wall of chips. Beyond the cap, a search box appears.
const TAG_FILTER_LIMIT = 12
const tagSearch = ref('')
const hasMoreTags = computed(() => availableTags.value.length > TAG_FILTER_LIMIT)

// Chips actually rendered. When searching, show every name match (no cap).
// Otherwise show the top-N frequent tags, but always append any selected tags
// that fell outside the cap so they stay deselectable.
const visibleTags = computed(() => {
  const q = tagSearch.value.trim().toLowerCase()
  if (q) return availableTags.value.filter(t => t.toLowerCase().includes(q))
  const top = availableTags.value.slice(0, TAG_FILTER_LIMIT)
  const extraActive = activeTags.value.filter(t => !top.includes(t) && availableTags.value.includes(t))
  return [...top, ...extraActive]
})

function toggleTag(tag: string) {
  const i = activeTags.value.indexOf(tag)
  if (i >= 0) activeTags.value.splice(i, 1)
  else activeTags.value.push(tag)
}
function clearTagFilter() {
  activeTags.value = []
}

const filteredAgents = computed(() => {
  let list = agents.value
  if (searchText.value) {
    const q = searchText.value.toLowerCase()
    list = list.filter(a =>
      a.name.toLowerCase().includes(q) ||
      a.description?.toLowerCase().includes(q) ||
      a.tags?.toLowerCase().includes(q)
    )
  }
  if (activeFilter.value === 'react') list = list.filter(a => a.agentType === 'react')
  else if (activeFilter.value === 'plan_execute') list = list.filter(a => a.agentType === 'plan_execute')
  else if (activeFilter.value === 'enabled') list = list.filter(a => a.enabled)
  else if (activeFilter.value === 'disabled') list = list.filter(a => !a.enabled)
  // Tag filter: intersection — an agent must carry every selected tag.
  if (activeTags.value.length) {
    list = list.filter(a => {
      const tags = agentTags(a)
      return activeTags.value.every(t => tags.includes(t))
    })
  }
  return list
})

// Roster ↔ Live view switch — admin only. The running/stuck counts feed the
// segmented control's pulse + badge so you know whether Live is worth a look.
const isAdminRole = computed(() => (localStorage.getItem('role') || 'user') === 'admin')
const view = ref<'roster' | 'live'>(
  route.query.view === 'live' && isAdminRole.value ? 'live' : 'roster',
)
const liveRunning = ref(0)
const liveStuck = ref(0)
let livePollTimer: ReturnType<typeof setInterval> | null = null

function setView(next: 'roster' | 'live') {
  view.value = next
  router.replace({ query: next === 'live' ? { view: 'live' } : {} })
}

async function refreshLiveCounts() {
  if (!isAdminRole.value) return
  try {
    const res: any = await liveApi.snapshot()
    const data = res?.data ?? res
    liveRunning.value = data?.summary?.running ?? 0
    liveStuck.value = data?.summary?.stuck ?? 0
  } catch {
    // Silent — stale value is preferable to a flapping number.
  }
}

onMounted(() => {
  loadAgents()
  // RFC-03 G1: load models once for the per-Agent override dropdown.
  // Failure is non-fatal — the dropdown just shows only "global default".
  loadAvailableModels()
  if (isAdminRole.value) {
    refreshLiveCounts()
    livePollTimer = setInterval(refreshLiveCounts, 10_000)
  }
})

onBeforeUnmount(() => {
  if (livePollTimer) clearInterval(livePollTimer)
})

async function loadAgents() {
  try {
    const res: any = await agentApi.list()
    agents.value = res.data || []
  } catch {
    mcToast.error(t('agents.messages.loadFailed'))
  }
}

async function loadAvailableModels() {
  try {
    const res: any = await modelApi.listEnabled()
    availableModels.value = (res.data || []).map((m: any) => ({
      id: m.id,
      name: m.name,
      provider: m.provider,
      modelName: m.modelName,
    }))
  } catch {
    // Silent — the picker still works (empty list = only "default" option).
  }
}

function openCreateModal() {
  // Show template selector first
  showTemplateSelector.value = true
  loadTemplates()
}

function openBlankCreateModal() {
  showTemplateSelector.value = false
  editingAgent.value = null
  form.value = defaultForm()
  profileForm.value = emptyProfile()
  modalTab.value = 'basic'
  skillBindingSearch.value = ''
  toolBindingSearch.value = ''
  selectedSkillIds.value = []
  selectedToolNames.value = []
  selectedProviderIds.value = []
  availableKBs.value = []
  selectedKBId.value = null
  selectedKbIds.value = []
  showModal.value = true
}

// RFC-009 PR-3: provider preference helpers
const unpickedProviders = computed(() =>
  availableProviders.value.filter(p => !selectedProviderIds.value.includes(p.id))
)

function providerNameById(id: string): string {
  return availableProviders.value.find(p => p.id === id)?.name || id
}

function addProvider(id: string) {
  if (!selectedProviderIds.value.includes(id)) {
    selectedProviderIds.value.push(id)
  }
}

function removeProvider(idx: number) {
  selectedProviderIds.value.splice(idx, 1)
}

function moveProvider(idx: number, dir: -1 | 1) {
  const next = idx + dir
  if (next < 0 || next >= selectedProviderIds.value.length) return
  const arr = selectedProviderIds.value
  ;[arr[idx], arr[next]] = [arr[next], arr[idx]]
}

async function loadTemplates() {
  try {
    const res: any = await templateApi.list()
    templates.value = res.data || []
  } catch {
    // Fallback: skip templates, open blank form
    openBlankCreateModal()
  }
}

async function applyTemplate(id: string) {
  applyingTemplate.value = true
  try {
    await templateApi.apply(id)
    mcToast.success(t('agents.templates.applied'))
    showTemplateSelector.value = false
    await loadAgents()
  } catch (e: any) {
    mcToast.error(e?.message || t('agents.messages.saveFailed'))
  } finally {
    applyingTemplate.value = false
  }
}

async function openEditModal(agent: Agent) {
  editingAgent.value = agent
  form.value = {
    name: agent.name,
    description: agent.description || '',
    agentType: agent.agentType,
    systemPrompt: agent.systemPrompt || '',
    modelName: agent.modelName || '',
    maxIterations: agent.maxIterations,
    icon: agent.icon || '',
    tags: agent.tags || '',
    enabled: agent.enabled,
    defaultThinkingLevel: (agent as any).defaultThinkingLevel || null,
    workspaceBasePath: agent.workspaceBasePath || undefined,
    primaryKbId: agent.primaryKbId != null ? String(agent.primaryKbId) : null,
    skillsDisabled: agent.skillsDisabled === true,
    toolsDisabled: agent.toolsDisabled === true,
  }
  profileForm.value = parsePrompt(agent.systemPrompt)
  modalTab.value = 'basic'
  skillBindingSearch.value = ''
  toolBindingSearch.value = ''
  showModal.value = true

  // Load available skills/tools/providers and current bindings in parallel
  try {
    const [skillsRes, toolsRes, providersRes, boundSkillsRes, boundToolsRes, providerPrefsRes] = await Promise.all([
      // RFC-042: /skills is now paginated; binding dropdown only needs enabled skills,
      // so listEnabled() is both semantically correct and shape-stable (returns array).
      skillApi.listEnabled(),
      // /tools/available aggregates built-in tools + every MCP-discovered
      // tool grouped by server, with stale/available flags so the picker
      // matches the runtime callback set exactly.
      toolApi.listAvailable(),
      modelApi.listProviders(),
      agentBindingApi.listSkills(agent.id),
      agentBindingApi.listTools(agent.id),
      agentBindingApi.listProviderPreferences(agent.id),
    ])
    availableSkills.value = (skillsRes as any).data || []
    availableTools.value = (toolsRes as any).data || []
    // Pool of providers the user has actually configured — no point letting an
    // agent prefer a provider that doesn't exist on this deployment.
    availableProviders.value = ((providersRes as any).data || [])
      .filter((p: any) => p.configured)
      .map((p: any) => ({ id: p.id, name: p.name }))
    selectedSkillIds.value = ((boundSkillsRes as any).data || [])
      .filter((b: any) => b.enabled)
      .map((b: any) => b.skillId)
    selectedToolNames.value = ((boundToolsRes as any).data || [])
      .filter((b: any) => b.enabled)
      .map((b: any) => b.toolName)
    selectedProviderIds.value = ((providerPrefsRes as any).data || [])
      .filter((b: any) => b.enabled)
      .map((b: any) => b.providerId)
  } catch {
    mcToast.error(t('agents.messages.loadFailed'))
  }

  // KB request is caught separately so its error message is accurate.
  try {
    const [kbsRes, kbBindRes]: any = await Promise.all([
      wikiApi.listBindableKBs(),
      agentBindingApi.listKbs(agent.id),
    ])
    const bindableKBs = (kbsRes.data || []) as any[]
    availableKBs.value = bindableKBs
    const bindableIds = new Set(bindableKBs.map((kb: any) => String(kb.id)))
    // Access scope: keep only enabled rows that still resolve to a visible KB.
    selectedKbIds.value = ((kbBindRes.data || []) as any[])
      .filter((b: any) => b.enabled)
      .map((b: any) => String(b.kbId))
      .filter((id: string) => bindableIds.has(id))
    const primaryKbId = agent.primaryKbId != null ? String(agent.primaryKbId) : null
    selectedKBId.value = primaryKbId && bindableIds.has(primaryKbId)
      ? primaryKbId
      : null
  } catch {
    mcToast.error(t('agents.binding.wikiLoadFailed'))
  }
}

function closeModal() {
  showModal.value = false
  editingAgent.value = null
  skillBindingSearch.value = ''
  toolBindingSearch.value = ''
  availableKBs.value = []
  selectedKBId.value = null
  selectedKbIds.value = []
}

async function saveAgent() {
  try {
    // Flatten the structured profile back to a single systemPrompt before
    // sending to the backend — the schema is unchanged, only the editor
    // exposes the H2 sections to the user.
    const serialized = serializePrompt(profileForm.value)
    const payload = { ...form.value, systemPrompt: serialized, primaryKbId: selectedKBId.value }

    let agentId: string | number
    if (editingAgent.value) {
      await agentApi.update(editingAgent.value.id, payload)
      agentId = editingAgent.value.id
    } else {
      const res: any = await agentApi.create(payload)
      agentId = res.data?.id
    }

    // Sequential binding saves (issue #184). Two coupled concerns:
    //
    // 1. Disabled-flag intent must win over stale picks. The opt-out
    //    toggles only disable the picker visually — selectedSkillIds /
    //    selectedToolNames keep whatever was previously bound. If we sent
    //    those stale picks to setSkills/setTools while the flag is on, the
    //    backend's auto-clear self-heals the flag back to false (because
    //    "non-empty save = concrete commitment"), and the user's "disable
    //    everything" intent vanishes silently. So we clear the array here
    //    before sending — saving [] preserves the flag, and the runtime
    //    contract ("flag wins over rows") is honored.
    //
    // 2. Sequential order, not Promise.all. Parallel binding calls would
    //    leave half-applied state on a partial failure; serial means we
    //    know exactly which side persisted and can pull the authoritative
    //    server state back if anything throws.
    const skillIdsToSave = form.value.skillsDisabled ? [] : selectedSkillIds.value
    const toolNamesToSave = form.value.toolsDisabled ? [] : selectedToolNames.value

    if (agentId && editingAgent.value) {
      try {
        await agentBindingApi.setSkills(agentId, skillIdsToSave)
        await agentBindingApi.setTools(agentId, toolNamesToSave)
        await agentBindingApi.setProviderPreferences(agentId, selectedProviderIds.value)
        // KB access scope. Empty = unrestricted (workspace-wide). Sent as
        // strings per the Snowflake-precision contract.
        await agentBindingApi.setKbs(agentId, selectedKbIds.value)
      } catch (bindingError: any) {
        mcToast.error(bindingError?.message || t('agents.messages.saveFailed'))
        // Pull the authoritative server state back into the editing form so
        // the user sees what actually persisted instead of stale picks.
        try {
          const fresh: any = await agentApi.get(agentId)
          if (fresh?.data) {
            await openEditModal(fresh.data)
          }
        } catch {
          // Reload failure on top of binding failure: best effort, stop here.
        }
        return
      }
    }

    mcToast.success(t('agents.messages.saveSuccess'))
    closeModal()
    await loadAgents()
  } catch (e: any) {
    mcToast.error(e?.message || t('agents.messages.saveFailed'))
  }
}

async function deleteAgent(agent: Agent) {
  const ok = await mcConfirm({
    title: t('agents.actions.delete'),
    message: t('agents.messages.deleteConfirm'),
    tone: 'danger',
  })
  if (!ok) return
  try {
    await agentApi.delete(agent.id)
    mcToast.success(t('agents.messages.deleteSuccess'))
    await loadAgents()
  } catch {
    mcToast.error(t('agents.messages.deleteFailed'))
  }
}

function goToAgentContextFor(agent: Agent) {
  router.push({ path: '/settings/agent-context', query: { agentId: String(agent.id) } })
}

/** Card primary action: open a chat with this agent. */
function goToChat(agent: Agent) {
  router.push({ path: '/chat', query: { agentId: String(agent.id) } })
}

async function toggleAgent(agent: Agent) {
  try {
    await agentApi.update(agent.id, { ...agent, enabled: !agent.enabled })
    mcToast.success(t('agents.messages.toggleSuccess'))
    await loadAgents()
  } catch {
    mcToast.error(t('agents.messages.toggleFailed'))
  }
}
</script>

<style scoped>
.agents-page { gap: 18px; }

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* ===== Roster / Live segmented switch ===== */
/* Lives in the header row, level with the New Employee button. */
.view-switch {
  display: inline-flex;
  background: var(--mc-bg-sunken);
  border: 1px solid var(--mc-border-light);
  border-radius: 999px;
  padding: 4px;
  gap: 2px;
}

.view-seg {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 18px;
  border-radius: 999px;
  border: none;
  background: transparent;
  color: var(--mc-text-secondary);
  font-size: 13.5px;
  font-weight: 500;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease, box-shadow 0.2s ease;
}

.view-seg:hover {
  color: var(--mc-text-primary);
}

.view-seg.is-active {
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

/* Pulsing dot — the Live segment is alive when runs are in flight. */
.seg-pulse {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: hsl(140, 55%, 48%);
  animation: seg-pulse 2.4s ease-in-out infinite;
}

@keyframes seg-pulse {
  0%, 100% { box-shadow: 0 0 0 0 hsla(140, 55%, 50%, 0.5); }
  50%      { box-shadow: 0 0 0 5px hsla(140, 55%, 50%, 0); }
}

.seg-count {
  font-family: ui-monospace, SFMono-Regular, 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 10.5px;
  font-variant-numeric: tabular-nums;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--mc-bg-muted);
  color: var(--mc-text-tertiary);
}

.view-seg.is-active .seg-count {
  background: var(--mc-accent-soft);
  color: var(--mc-accent);
}

/* Stuck runs turn the badge warm — you should look without switching. */
.seg-count.warn {
  background: hsla(20, 90%, 55%, 0.18);
  color: hsl(20, 75%, 42%);
}

html.dark .seg-count.warn {
  color: hsl(28, 80%, 70%);
}

.btn-primary { display: flex; align-items: center; gap: 6px; padding: 10px 16px; background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover)); color: white; border: none; border-radius: 14px; font-size: 14px; font-weight: 600; cursor: pointer; transition: background 0.15s, transform 0.15s; box-shadow: var(--mc-shadow-soft); }
.btn-primary:hover { background: var(--mc-primary-hover); }
.btn-primary:disabled { background: var(--mc-border); cursor: not-allowed; }
.btn-secondary { padding: 8px 16px; background: var(--mc-bg-elevated); color: var(--mc-text-primary); border: 1px solid var(--mc-border); border-radius: 12px; font-size: 14px; cursor: pointer; }
.btn-secondary:hover { background: var(--mc-bg-sunken); }

.agents-toolbar { padding: 18px; }
.filter-bar { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.search-box { display: flex; align-items: center; gap: 8px; background: var(--mc-bg-muted); border: 1px solid var(--mc-border); border-radius: 14px; padding: 10px 12px; flex: 1; max-width: 360px; }
.search-box svg { color: var(--mc-text-tertiary); flex-shrink: 0; }
.search-input { border: none; outline: none; font-size: 14px; color: var(--mc-text-primary); flex: 1; background: transparent; }
.filter-tabs { display: flex; gap: 6px; flex-wrap: wrap; }
.filter-tab { padding: 8px 14px; border: 1px solid var(--mc-border); background: var(--mc-bg-muted); border-radius: 999px; font-size: 13px; color: var(--mc-text-secondary); cursor: pointer; transition: all 0.15s; font-weight: 600; }
.filter-tab:hover { background: var(--mc-bg-sunken); }
.filter-tab.active { background: var(--mc-primary-bg); border-color: var(--mc-primary); color: var(--mc-primary); font-weight: 500; }
.tag-filter-bar { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-top: 12px; padding-top: 12px; border-top: 1px solid var(--mc-border); }
.tag-filter-bar__label { font-size: 12px; color: var(--mc-text-tertiary); font-weight: 600; margin-right: 2px; }
.tag-filter-chip { padding: 4px 12px; border: 1px solid var(--mc-border); background: var(--mc-bg-muted); border-radius: 999px; font-size: 12px; color: var(--mc-text-secondary); cursor: pointer; transition: all 0.15s; }
.tag-filter-chip:hover { background: var(--mc-bg-sunken); }
.tag-filter-chip.active { background: var(--mc-primary-bg); border-color: var(--mc-primary); color: var(--mc-primary); font-weight: 600; }
.tag-filter-search { padding: 4px 10px; border: 1px solid var(--mc-border); background: var(--mc-bg-muted); border-radius: 999px; font-size: 12px; color: var(--mc-text-primary); width: 120px; outline: none; }
.tag-filter-search:focus { border-color: var(--mc-primary); }
.tag-filter-bar__empty { font-size: 12px; color: var(--mc-text-tertiary); }
.tag-filter-clear { padding: 4px 10px; border: none; background: transparent; font-size: 12px; color: var(--mc-text-tertiary); cursor: pointer; text-decoration: underline; }
.tag-filter-clear:hover { color: var(--mc-text-secondary); }

/* Agent Card Grid */
.agent-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}

.agent-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px;
  transition: all 0.15s;
  cursor: default;
}

.agent-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
}

.agent-card--disabled {
  opacity: 0.55;
}

/* Employee-card layout — identity row on top, primary action row below.
   The card answers one question: "Who is this and how do I talk to them?" */
.agent-card__top {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.agent-card__avatar {
  width: 56px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--mc-primary-bg);
  border-radius: 18px;
  flex-shrink: 0;
  font-size: 32px;
  transition: filter 0.18s;
}

.agent-card__avatar--off {
  filter: grayscale(0.85);
}

.agent-card__identity {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.agent-card__name {
  font-size: 17px;
  font-weight: 700;
  color: var(--mc-text-primary);
  margin: 0;
  letter-spacing: -0.02em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* The tagline IS the soul of the card. Keep it on one line so the eye reads
   it as a single statement of identity — never wrap, never grow. */
.agent-card__tagline {
  font-size: 13.5px;
  color: var(--mc-text-secondary);
  margin: 0;
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  letter-spacing: -0.005em;
}

.agent-card__tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
.agent-card__tag { font-size: 11px; padding: 2px 8px; background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); border-radius: 999px; white-space: nowrap; cursor: pointer; transition: all 0.15s; }
.agent-card__tag:hover { color: var(--mc-text-secondary); }
.agent-card__tag.active { background: var(--mc-primary-bg); color: var(--mc-primary); font-weight: 600; }

.agent-card__action-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid var(--mc-border-light);
}

.agent-card__primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
  border: 1px solid var(--mc-border);
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}
.agent-card__primary:hover {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
  background: var(--mc-primary-bg);
}

.agent-card__overflow {
  display: flex;
  align-items: center;
  gap: 4px;
  opacity: 0;
  transition: opacity 0.18s;
}
.agent-card:hover .agent-card__overflow,
.agent-card:focus-within .agent-card__overflow {
  opacity: 1;
}

.toggle-switch--sm { width: 32px; height: 18px; }
.toggle-switch--sm .toggle-slider::before { width: 12px; height: 12px; }
.toggle-switch--sm input:checked + .toggle-slider::before { transform: translateX(14px); }

.tag { padding: 2px 8px; border-radius: 10px; font-size: 11px; font-weight: 500; }
.tag-item { background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }
.text-muted { color: var(--mc-text-tertiary); }

.toggle-switch { position: relative; display: inline-block; width: 36px; height: 20px; cursor: pointer; flex-shrink: 0; }
.toggle-switch input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; inset: 0; background: var(--mc-border); border-radius: 20px; transition: 0.2s; }
.toggle-slider::before { content: ''; position: absolute; width: 14px; height: 14px; left: 3px; top: 3px; background: var(--mc-bg-elevated); border-radius: 50%; transition: 0.2s; }
.toggle-switch input:checked + .toggle-slider { background: var(--mc-primary); }
.toggle-switch input:checked + .toggle-slider::before { transform: translateX(16px); }

.action-btns { display: flex; gap: 4px; }
.action-btn { width: 30px; height: 30px; border: 1px solid var(--mc-border); background: var(--mc-bg-elevated); border-radius: 6px; display: flex; align-items: center; justify-content: center; cursor: pointer; color: var(--mc-text-secondary); transition: all 0.15s; }
.action-btn:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.action-btn.danger:hover { background: var(--mc-danger-bg); border-color: var(--mc-danger); color: var(--mc-danger); }

/* Empty state */
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 80px 20px; text-align: center; }
.empty-icon { font-size: 48px; margin-bottom: 16px; }
.empty-state h3 { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0 0 8px; }
.empty-state p { font-size: 14px; color: var(--mc-text-tertiary); margin: 0 0 24px; }

/* Modal */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 20px; }
.modal { background: var(--mc-bg-elevated); border: 1px solid var(--mc-border); border-radius: 16px; width: 100%; max-width: 600px; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 20px 60px rgba(0,0,0,0.15); }
.modal-header { display: flex; align-items: center; justify-content: space-between; padding: 20px 24px; border-bottom: 1px solid var(--mc-border-light); }
.modal-header h2 { font-size: 18px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.modal-close { width: 32px; height: 32px; border: none; background: none; cursor: pointer; color: var(--mc-text-tertiary); display: flex; align-items: center; justify-content: center; border-radius: 6px; }
.modal-close:hover { background: var(--mc-bg-sunken); color: var(--mc-text-primary); }
.modal-body { flex: 1; overflow-y: auto; padding: 20px 24px; }

/* Modal Tabs */
.modal-tabs { display: flex; gap: 4px; margin-bottom: 20px; border-bottom: 1px solid var(--mc-border-light); padding-bottom: 0; }
.modal-tab {
  padding: 8px 16px; border: none; background: none; cursor: pointer;
  font-size: 13px; font-weight: 500; color: var(--mc-text-tertiary);
  border-bottom: 2px solid transparent; margin-bottom: -1px; transition: all 0.15s;
  display: inline-flex; align-items: center; gap: 6px;
}
.modal-tab:hover { color: var(--mc-text-primary); }
.modal-tab.active { color: var(--mc-primary); border-bottom-color: var(--mc-primary); }
.tab-badge {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 18px; height: 18px; padding: 0 5px;
  border-radius: 9px; background: var(--mc-primary); color: white;
  font-size: 11px; font-weight: 600;
}
/* Issue #184: "off" variant for the disable-all state. Neutral grey instead
   of brand orange because the badge represents a constraint, not a count. */
.tab-badge--off {
  min-width: auto; padding: 0 8px;
  background: var(--mc-bg-sunken, rgba(0,0,0,0.08));
  color: var(--mc-text-tertiary);
  border: 1px solid var(--mc-border-light);
}

/* Binding Tab */
.binding-tab { min-height: 200px; }
.binding-hint { font-size: 13px; color: var(--mc-text-tertiary); margin: 0 0 16px; }

/* Tools/Skills semantic intro — names what the user is about to bind so the
   distinction between "atomic tool" and "trained workflow" is unmissable. */
.binding-intro {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 14px;
  margin: 0 0 14px;
  background: var(--mc-bg-muted);
  border-left: 3px solid var(--mc-primary);
  border-radius: 8px;
}
.binding-intro__kicker {
  font-size: 11px;
  font-weight: 700;
  color: var(--mc-primary);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}
.binding-intro__tagline {
  font-size: 13px;
  color: var(--mc-text-secondary);
  line-height: 1.5;
  margin: 0;
}
.binding-empty { padding: 40px; text-align: center; color: var(--mc-text-tertiary); font-size: 14px; }
.binding-empty--compact { padding: 24px 12px; }
/* Issue #184 — opt-out row that sits above the picker list. */
.binding-disable-row {
  margin: 0 0 12px;
  padding: 12px 14px;
  border: 1px dashed var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-sunken);
}
.binding-disable-label {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  cursor: pointer;
}
.binding-disable-checkbox {
  flex-shrink: 0;
  accent-color: var(--mc-primary);
  width: 16px;
  height: 16px;
  margin-top: 2px;
}
.binding-disable-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
  color: var(--mc-text-primary);
  line-height: 1.4;
}
.binding-disable-text strong { font-weight: 600; }
.binding-disable-hint {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  line-height: 1.5;
}
/* When the opt-out is on, the picker list is still visible (so the user
   can see what they're disabling) but rendered inert — no hover affordance,
   greyed-out interactions. */
.binding-search--disabled,
.binding-list--disabled { opacity: 0.45; pointer-events: none; }
.binding-item--inert { cursor: not-allowed; }
.binding-search {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
  padding: 8px 10px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-sunken);
  color: var(--mc-text-tertiary);
}
.binding-search svg { flex-shrink: 0; }
.binding-search input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--mc-text-primary);
  font-size: 13px;
}
.binding-search input::placeholder { color: var(--mc-text-tertiary); }
.binding-list { display: flex; flex-direction: column; gap: 6px; }
.binding-item {
  display: flex; align-items: center; gap: 10px; padding: 10px 12px;
  border: 1px solid var(--mc-border-light); border-radius: 8px;
  cursor: pointer; transition: all 0.15s; background: var(--mc-bg);
}
.binding-item:hover { border-color: var(--mc-primary-light, rgba(217,119,87,0.3)); background: var(--mc-bg-elevated); }
.binding-item.selected { border-color: var(--mc-primary); background: rgba(217,119,87,0.04); }
.binding-checkbox { flex-shrink: 0; accent-color: var(--mc-primary); width: 16px; height: 16px; }
.binding-icon { font-size: 20px; flex-shrink: 0; }
.binding-info { flex: 1; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.binding-name { font-size: 14px; font-weight: 500; color: var(--mc-text-primary); }
.binding-desc { font-size: 12px; color: var(--mc-text-tertiary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
/* reused for KB pageCount badge in wiki tab */
.binding-version { font-size: 11px; color: var(--mc-text-tertiary); flex-shrink: 0; }
/* Highlighted scope hint when the agent is restricted to a KB subset */
.binding-hint--warn { color: var(--mc-warning, #b8860b); }
/* "Set default" / "Default" toggle for the primary KB in the wiki tab */
.kb-primary-toggle {
  flex-shrink: 0;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 6px;
  border: 1px solid var(--mc-border);
  background: transparent;
  color: var(--mc-text-tertiary);
  cursor: pointer;
  white-space: nowrap;
}
.kb-primary-toggle:hover { border-color: var(--mc-primary); color: var(--mc-primary); }
.kb-primary-toggle--active {
  background: var(--mc-primary);
  border-color: var(--mc-primary);
  color: #fff;
}
.binding-type-badge {
  font-size: 10px; padding: 2px 6px; border-radius: 4px; flex-shrink: 0;
  background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); text-transform: uppercase;
}

/* Provider preference list (RFC-009 PR-3) */
.provider-pref-list { display: flex; flex-direction: column; gap: 6px; }
.provider-pref-item {
  display: flex; align-items: center; gap: 10px; padding: 8px 12px;
  border: 1px solid var(--mc-border-light); border-radius: 8px; background: var(--mc-bg-elevated);
}
.provider-pref-rank {
  width: 22px; height: 22px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--mc-primary); color: white; font-size: 11px; font-weight: 700; flex-shrink: 0;
}
.provider-pref-name { font-size: 14px; color: var(--mc-text-primary); flex: 1; }
.provider-pref-id { font-size: 12px; color: var(--mc-text-tertiary); font-family: ui-monospace, monospace; }
.provider-pref-btn {
  border: 1px solid var(--mc-border-light); background: var(--mc-bg);
  width: 26px; height: 26px; border-radius: 6px; cursor: pointer;
  font-size: 12px; color: var(--mc-text-secondary);
}
.provider-pref-btn:disabled { opacity: 0.35; cursor: not-allowed; }
.provider-pref-btn:not(:disabled):hover { border-color: var(--mc-primary); color: var(--mc-primary); }
.provider-pref-btn.danger:not(:disabled):hover { border-color: var(--mc-danger); color: var(--mc-danger); }
.provider-pref-pool { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.provider-pref-pool .binding-hint { width: 100%; }
.provider-pref-add-btn {
  border: 1px dashed var(--mc-border); background: transparent;
  padding: 4px 10px; border-radius: 6px; font-size: 12px; cursor: pointer;
  color: var(--mc-text-secondary);
}
.provider-pref-add-btn:hover { border-color: var(--mc-primary); color: var(--mc-primary); border-style: solid; }

/* minmax(0, 1fr) prevents nowrap children (e.g. the tagline preview)
   from forcing the grid track wider than the modal body. */
.form-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; }
.form-group { display: flex; flex-direction: column; gap: 6px; min-width: 0; }
.form-group.full-width { grid-column: 1 / -1; }
.form-label { font-size: 13px; font-weight: 500; color: var(--mc-text-secondary); }
.form-input, .form-textarea { padding: 8px 12px; border: 1px solid var(--mc-border); border-radius: 8px; font-size: 14px; color: var(--mc-text-primary); outline: none; transition: border-color 0.15s; background: var(--mc-bg-sunken); }
.form-input:focus, .form-textarea:focus { border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.1); }
.form-textarea { resize: vertical; font-family: inherit; }

/* Inline guidance below an input. Used for the role/goal/backstory triad
   so the constraint (one short line, etc.) is visible while typing. */
.form-hint {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  line-height: 1.5;
  margin: 2px 0 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: baseline;
}
.form-hint__label {
  color: var(--mc-text-tertiary);
}
.form-hint__value {
  color: var(--mc-text-secondary);
  font-weight: 600;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.form-hint__counter {
  font-variant-numeric: tabular-nums;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  color: var(--mc-text-tertiary);
}
.form-hint--warn .form-hint__value,
.form-hint--warn .form-hint__counter {
  color: hsl(20, 78%, 48%);
}

/* Collapsible advanced-instructions block — the everyday user fills the
   triad and never opens this; power users can append free-form additions. */
.advanced-prompt { border: 1px dashed var(--mc-border); border-radius: 10px; padding: 0; }
.advanced-prompt[open] { padding: 12px 14px; }
.advanced-prompt__summary {
  list-style: none;
  cursor: pointer;
  padding: 10px 14px;
  font-size: 13px;
  font-weight: 600;
  color: var(--mc-text-secondary);
  user-select: none;
}
.advanced-prompt__summary::-webkit-details-marker { display: none; }
.advanced-prompt__summary::before {
  content: '▸';
  display: inline-block;
  margin-right: 6px;
  color: var(--mc-text-tertiary);
  font-size: 11px;
  transition: transform 0.15s;
}
.advanced-prompt[open] > .advanced-prompt__summary { padding: 0 0 8px; border-bottom: 1px solid var(--mc-border-light); margin-bottom: 8px; }
.advanced-prompt[open] > .advanced-prompt__summary::before { transform: rotate(90deg); }
.modal-footer { display: flex; justify-content: flex-end; gap: 10px; padding: 16px 24px; border-top: 1px solid var(--mc-border-light); }

@media (max-width: 900px) {
  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .search-box {
    max-width: none;
  }
}

/* Template Selector */
.template-modal { max-width: 640px; }
.template-desc { font-size: 14px; color: var(--mc-text-secondary); margin: 0 0 18px; }

.template-grid { display: flex; flex-direction: column; gap: 10px; }

.template-card {
  display: flex; align-items: flex-start; gap: 14px; padding: 16px; cursor: pointer;
  border: 1px solid var(--mc-border); border-radius: 12px; transition: all 0.15s;
}
.template-card:hover { border-color: var(--mc-primary); background: var(--mc-primary-bg); }
.template-card.applying { opacity: 0.5; pointer-events: none; }

.template-icon { font-size: 28px; width: 44px; height: 44px; display: flex; align-items: center; justify-content: center; background: var(--mc-bg-muted); border-radius: 10px; flex-shrink: 0; }

.template-info { flex: 1; min-width: 0; }
.template-name { font-size: 15px; font-weight: 600; color: var(--mc-text-primary); margin: 0 0 4px; }
.template-detail { font-size: 13px; color: var(--mc-text-secondary); margin: 0; line-height: 1.5; }

.template-tags { display: flex; flex-wrap: wrap; gap: 4px; align-self: flex-start; margin-top: 2px; }
.tag-chip { font-size: 11px; padding: 2px 8px; background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); border-radius: 999px; white-space: nowrap; }

/* RFC-090 §9.2 调整 B — Advanced Tools picker (collapsed by default) */
.advanced-tools { border: 1px dashed var(--mc-border); border-radius: 12px; padding: 0; }
.advanced-tools[open] { padding: 12px 14px; }
.advanced-tools-summary { list-style: none; cursor: pointer; padding: 12px 14px; display: flex; align-items: center; justify-content: space-between; gap: 8px; user-select: none; color: var(--mc-text-secondary); font-size: 13px; font-weight: 600; }
.advanced-tools-summary::-webkit-details-marker { display: none; }
.advanced-tools[open] > .advanced-tools-summary { padding: 0 0 8px; border-bottom: 1px solid var(--mc-border-light); margin-bottom: 8px; }
.advanced-tools-title { display: inline-flex; align-items: center; gap: 8px; }
.advanced-tools-count { padding: 1px 8px; background: var(--mc-primary-bg); color: var(--mc-primary); border-radius: 999px; font-size: 11px; font-weight: 700; }
.advanced-tools-chevron { color: var(--mc-text-tertiary); font-size: 12px; }
.advanced-tools-note { font-style: italic; color: var(--mc-text-tertiary); margin-top: 4px; }

/* MCP group header: inline "auto-available" tag next to the label so users
   know enabled MCP tools work without being ticked. */
.binding-group-header { display: flex; align-items: center; gap: 8px; }
.binding-group-note {
  font-size: 11px;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 10px;
  background: color-mix(in srgb, var(--mc-primary) 12%, transparent);
  color: var(--mc-primary);
  cursor: help;
}

/* Icon picker trigger — replaces the old free-text icon input. Tile shape
 * mirrors SkillMarket's identity-icon-row so the create/edit affordance
 * is consistent across the app. */
.icon-picker-trigger {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-sunken);
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s, background 0.15s;
  width: 100%;
}
.icon-picker-trigger:hover { border-color: var(--mc-primary); background: var(--mc-bg-elevated); }
.icon-picker-trigger:focus-visible { outline: none; border-color: var(--mc-primary); box-shadow: 0 0 0 2px rgba(217,119,87,0.15); }
.icon-picker-trigger__label {
  flex: 1; min-width: 0;
  font-size: 13px;
  color: var(--mc-text-primary);
  font-family: 'JetBrains Mono', 'Fira Code', 'Consolas', monospace;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.icon-picker-trigger__action {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--mc-primary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
</style>
