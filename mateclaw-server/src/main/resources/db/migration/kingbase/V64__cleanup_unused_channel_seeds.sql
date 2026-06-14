-- RFC-084 follow-up: see h2/V64 for the rationale. MySQL accepts the
-- same DELETE directly — DELETE on no matching rows is a no-op, not an
-- error, so no INFORMATION_SCHEMA guard is needed.

DELETE FROM mate_channel
WHERE id IN (1000000002, 1000000003, 1000000004, 1000000005,
             1000000006, 1000000007, 1000000008, 1000000009)
  AND channel_type IN ('dingtalk', 'feishu', 'telegram', 'discord',
                       'wecom', 'qq', 'weixin', 'slack')
  AND enabled = FALSE;
