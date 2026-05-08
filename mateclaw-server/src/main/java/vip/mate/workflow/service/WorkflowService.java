package vip.mate.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.workflow.compiler.PublishContext;
import vip.mate.workflow.compiler.WorkflowAclPort;
import vip.mate.workflow.compiler.WorkflowCompiler;
import vip.mate.workflow.model.WorkflowEntity;
import vip.mate.workflow.model.WorkflowRevisionEntity;
import vip.mate.workflow.repository.WorkflowMapper;
import vip.mate.workflow.repository.WorkflowRevisionMapper;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Workflow CRUD + draft / publish lifecycle. Drafts live inline on the
 * {@code mate_workflow} row; publishing compiles the draft and writes a
 * fresh row into {@code mate_workflow_revision} with a monotonically
 * increasing per-workflow revision number, then atomically points
 * {@code latest_revision_id} at it.
 */
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowMapper workflowMapper;
    private final WorkflowRevisionMapper revisionMapper;
    private final WorkflowCompiler compiler;
    private final WorkflowAclPort aclPort;

    public List<WorkflowEntity> listByWorkspace(long workspaceId) {
        return workflowMapper.selectList(new LambdaQueryWrapper<WorkflowEntity>()
                .eq(WorkflowEntity::getWorkspaceId, workspaceId)
                .orderByDesc(WorkflowEntity::getUpdateTime));
    }

    /**
     * Workspace-scoped lookup. All read paths that take a raw {@code id}
     * must use this so callers can't fetch a row from another tenant just
     * by guessing a numeric id. Returns {@code null} when the row exists
     * but lives in a different workspace (treated as "not found" so the
     * caller doesn't get a side-channel signal that the id is real).
     */
    public WorkflowEntity get(long id, long workspaceId) {
        WorkflowEntity row = workflowMapper.selectById(id);
        if (row == null) return null;
        if (row.getWorkspaceId() == null || row.getWorkspaceId() != workspaceId) return null;
        return row;
    }

    /**
     * Same as {@link #get(long, long)} but throws when the row is missing.
     * Used by mutation paths that can fail loudly instead of returning null.
     */
    private WorkflowEntity getOrThrow(long id, long workspaceId) {
        WorkflowEntity row = get(id, workspaceId);
        if (row == null) {
            throw new IllegalArgumentException("workflow not found: " + id);
        }
        return row;
    }

    @Transactional
    public WorkflowEntity create(WorkflowEntity workflow) {
        if (workflow.getEnabled() == null) workflow.setEnabled(true);
        workflowMapper.insert(workflow);
        return workflow;
    }

    /**
     * Update workflow metadata (name / description / enabled). The patch
     * shape is deliberately narrow: the caller cannot replace
     * {@code draftJson}, {@code latest_revision_id}, or {@code workspace_id}
     * through this path. Without that narrowing, a metadata-only save from
     * the UI would clobber the draft because the request body wouldn't
     * carry it.
     */
    @Transactional
    public WorkflowEntity updateMetadata(long id, long workspaceId, String name,
                                         String description, Boolean enabled) {
        WorkflowEntity existing = getOrThrow(id, workspaceId);
        if (name != null) existing.setName(name);
        if (description != null) existing.setDescription(description);
        if (enabled != null) existing.setEnabled(enabled);
        // draftJson / latest_revision_id / workspace_id are intentionally
        // left untouched here — those move only through saveDraft / publish.
        workflowMapper.updateById(existing);
        return existing;
    }

    @Transactional
    public WorkflowEntity saveDraft(long id, long workspaceId, String draftJson, Long updatedBy) {
        WorkflowEntity row = getOrThrow(id, workspaceId);
        row.setDraftJson(draftJson);
        row.setDraftUpdatedAt(LocalDateTime.now());
        row.setDraftUpdatedBy(updatedBy);
        workflowMapper.updateById(row);
        return row;
    }

    @Transactional
    public void delete(long id, long workspaceId) {
        getOrThrow(id, workspaceId);
        workflowMapper.deleteById(id);
    }

    /**
     * Compile the workflow's current draft and persist it as a new revision
     * pointed at by {@code latest_revision_id}. Throws
     * {@link vip.mate.workflow.compiler.WorkflowCompileFailedException} when
     * the compiler reports any errors.
     */
    @Transactional
    public PublishOutcome publish(long workflowId, long workspaceId, Long publisherId, String publishedNote) {
        // Row-lock the workflow for the entire publish transaction so two
        // concurrent publishes serialize on the same monotonic next revision
        // — without this, both compute max+1 and the second one trips
        // uk_workflow_revision while leaving the latest_revision_id pointer
        // ambiguous.
        WorkflowEntity workflow = workflowMapper.selectByIdForUpdate(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("workflow not found: " + workflowId);
        }
        if (workflow.getWorkspaceId() == null || workflow.getWorkspaceId() != workspaceId) {
            // Cross-workspace publish attempt — same surface as "not found"
            // so the caller can't probe id existence by error message.
            throw new IllegalArgumentException("workflow not found: " + workflowId);
        }
        String draft = workflow.getDraftJson();
        if (draft == null || draft.isBlank()) {
            throw new IllegalStateException("cannot publish workflow " + workflowId
                    + " without a draft");
        }
        PublishContext ctx = new PublishContext(publisherId == null ? 0L : publisherId,
                workflow.getWorkspaceId());
        WorkflowCompiler.Result compileResult = compiler.compile(draft, ctx, aclPort);
        compileResult.requireOk();

        int nextRevision = nextRevisionNumber(workflowId);
        WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
        revision.setWorkflowId(workflowId);
        revision.setRevision(nextRevision);
        revision.setGraphJson(draft);
        revision.setSchemaVersion(compileResult.graph().schemaVersion() == null
                ? "1.0" : compileResult.graph().schemaVersion());
        revision.setPublishedNote(publishedNote);
        revision.setPublishedBy(publisherId);
        revisionMapper.insert(revision);

        workflow.setLatestRevisionId(revision.getId());
        workflowMapper.updateById(workflow);
        return new PublishOutcome(workflow, revision);
    }

    private int nextRevisionNumber(long workflowId) {
        WorkflowRevisionEntity max = revisionMapper.selectOne(new LambdaQueryWrapper<WorkflowRevisionEntity>()
                .eq(WorkflowRevisionEntity::getWorkflowId, workflowId)
                .orderByDesc(WorkflowRevisionEntity::getRevision)
                .last("LIMIT 1"));
        return max == null ? 1 : max.getRevision() + 1;
    }

    /** Snapshot returned to controllers after a successful publish. */
    public record PublishOutcome(WorkflowEntity workflow, WorkflowRevisionEntity revision) {}
}
