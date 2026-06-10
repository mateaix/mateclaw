-- ============================================================
-- 修复：知识库原始材料重复入库
-- 适用：MySQL 8.0+（使用 JSON 函数处理 source_raw_ids）
-- 说明：同一 (kb_id, source_path) 可能因文件内容变更
--       被多次 INSERT 而形成多行。本脚本保留最新行，
--       并级联清理其关联的 chunk、citation、page。
-- ============================================================

-- ──────────────────────────────────────────────────────────
-- STEP 0：预览（只读，不改数据，先跑这一步确认影响范围）
-- ──────────────────────────────────────────────────────────

-- 0-A：查看所有重复组（按 kb_id + source_path 分组，count > 1）
SELECT
    kb_id,
    source_path,
    COUNT(*)        AS duplicate_count,
    MAX(id)         AS keep_id,
    GROUP_CONCAT(id ORDER BY id DESC) AS all_ids
FROM mate_wiki_raw_material
WHERE source_path IS NOT NULL
GROUP BY kb_id, source_path
HAVING COUNT(*) > 1;

-- 0-B：查看待删除的具体行（排除每组最新的那一行）
SELECT
    r.id, r.kb_id, r.source_path,
    r.content_hash, r.processing_status, r.create_time
FROM mate_wiki_raw_material r
WHERE r.source_path IS NOT NULL
  AND r.id NOT IN (
      SELECT MAX(id)
      FROM mate_wiki_raw_material
      WHERE source_path IS NOT NULL
      GROUP BY kb_id, source_path
  )
ORDER BY r.kb_id, r.source_path, r.id;


-- ──────────────────────────────────────────────────────────
-- STEP 1：开事务，执行清理（确认 STEP 0 结果后再运行）
-- ──────────────────────────────────────────────────────────

START TRANSACTION;

-- 1-A：把待删除的 raw id 暂存到临时表，后续步骤复用
CREATE TEMPORARY TABLE IF NOT EXISTS _stale_raw_ids AS
SELECT id AS raw_id, kb_id
FROM mate_wiki_raw_material
WHERE source_path IS NOT NULL
  AND id NOT IN (
      SELECT MAX(id)
      FROM mate_wiki_raw_material
      WHERE source_path IS NOT NULL
      GROUP BY kb_id, source_path
  );

-- 1-B：删除这些 raw 产生的 citation（通过 chunk_id 关联）
DELETE c
FROM mate_wiki_page_citation c
         INNER JOIN mate_wiki_chunk ch ON c.chunk_id = ch.id
         INNER JOIN _stale_raw_ids s ON ch.raw_id = s.raw_id;

-- 1-C：删除 chunk
DELETE ch
FROM mate_wiki_chunk ch
         INNER JOIN _stale_raw_ids s ON ch.raw_id = s.raw_id;

-- 1-D：删除仅由该 raw 派生的 page（source_raw_ids 数组长度为 1）
--      使用 JSON_CONTAINS 判断 page 是否引用了待删 raw
DELETE p
FROM mate_wiki_page p
WHERE JSON_LENGTH(p.source_raw_ids) = 1
  AND EXISTS (
      SELECT 1
      FROM _stale_raw_ids s
      WHERE JSON_CONTAINS(p.source_raw_ids, CAST(s.raw_id AS CHAR))
  );

-- 1-E：对多来源 page，将待删 raw 从 source_raw_ids 中移除
--      通过 JSON_TABLE 把数组展开再重组，排除掉 stale raw id
UPDATE mate_wiki_page p
SET p.source_raw_ids = (
    SELECT JSON_ARRAYAGG(jt.v)
    FROM JSON_TABLE(p.source_raw_ids, '$[*]' COLUMNS (v BIGINT PATH '$')) jt
    WHERE jt.v NOT IN (SELECT raw_id FROM _stale_raw_ids)
)
WHERE JSON_LENGTH(p.source_raw_ids) > 1
  AND EXISTS (
      SELECT 1
      FROM _stale_raw_ids s
      WHERE JSON_CONTAINS(p.source_raw_ids, CAST(s.raw_id AS CHAR))
  );

-- 1-F：删除 stale raw 行
DELETE r
FROM mate_wiki_raw_material r
         INNER JOIN _stale_raw_ids s ON r.id = s.raw_id;

-- 1-G：确认结果
SELECT
    'stale raws deleted'  AS action,
    ROW_COUNT()           AS affected_rows;

SELECT
    'remaining duplicates' AS check_item,
    COUNT(*)               AS count
FROM mate_wiki_raw_material
WHERE source_path IS NOT NULL
GROUP BY kb_id, source_path
HAVING COUNT(*) > 1;

-- 确认无误后提交；如有问题改为 ROLLBACK
COMMIT;
-- ROLLBACK;

DROP TEMPORARY TABLE IF EXISTS _stale_raw_ids;
