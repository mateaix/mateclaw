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

    public WorkflowEntity get(long id) {
        return workflowMapper.selectById(id);
    }

    @Transactional
    public WorkflowEntity create(WorkflowEntity workflow) {
        if (workflow.getEnabled() == null) workflow.setEnabled(true);
        workflowMapper.insert(workflow);
        return workflow;
    }

    @Transactional
    public WorkflowEntity update(WorkflowEntity workflow) {
        WorkflowEntity existing = workflowMapper.selectById(workflow.getId());
        if (existing == null) {
            throw new IllegalArgumentException("workflow not found: " + workflow.getId());
        }
        // Preserve revision pointer — only the publish path moves it.
        workflow.setLatestRevisionId(existing.getLatestRevisionId());
        workflowMapper.updateById(workflow);
        return workflow;
    }

    @Transactional
    public WorkflowEntity saveDraft(long id, String draftJson, Long updatedBy) {
        WorkflowEntity row = workflowMapper.selectById(id);
        if (row == null) throw new IllegalArgumentException("workflow not found: " + id);
        row.setDraftJson(draftJson);
        row.setDraftUpdatedAt(LocalDateTime.now());
        row.setDraftUpdatedBy(updatedBy);
        workflowMapper.updateById(row);
        return row;
    }

    @Transactional
    public void delete(long id) {
        workflowMapper.deleteById(id);
    }

    /**
     * Compile the workflow's current draft and persist it as a new revision
     * pointed at by {@code latest_revision_id}. Throws
     * {@link vip.mate.workflow.compiler.WorkflowCompileFailedException} when
     * the compiler reports any errors.
     */
    @Transactional
    public PublishOutcome publish(long workflowId, Long publisherId, String publishedNote) {
        WorkflowEntity workflow = workflowMapper.selectById(workflowId);
        if (workflow == null) {
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
