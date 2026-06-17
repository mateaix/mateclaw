package vip.mate.channel.webchat.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.channel.webchat.WebChatRevokedVisitorEntity;

/**
 * Mapper for {@link WebChatRevokedVisitorEntity}. Lives under {@code repository}
 * so the application-wide {@code @MapperScan("vip.mate.**.repository")} picks it up.
 */
@Mapper
public interface WebChatRevokedVisitorMapper extends BaseMapper<WebChatRevokedVisitorEntity> {
}
