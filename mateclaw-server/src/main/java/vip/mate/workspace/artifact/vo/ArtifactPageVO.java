package vip.mate.workspace.artifact.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Paginated artifact listing (issue #514).
 *
 * <p>Deliberately a plain VO rather than a MyBatis-Plus {@code IPage} so the
 * external contract ({@code total / page / size / hasMore}) is stable and
 * decoupled from the ORM's page type — the WebChat consumer deserialises this
 * without pulling in MyBatis-Plus.
 *
 * @author MateClaw Team
 */
@Data
@Builder
public class ArtifactPageVO {

    private List<ArtifactVO> items;

    private long total;

    private int page;

    private int size;

    private boolean hasMore;
}
