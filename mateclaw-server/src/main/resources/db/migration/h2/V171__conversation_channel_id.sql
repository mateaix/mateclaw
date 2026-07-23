-- WebChat channel isolation (#558): persist the channel that owns each webchat
-- conversation so /sessions can filter by channel exactly, instead of matching
-- the conversationId prefix (which collided across channels because all generated
-- apiKeys share the 8-char slice "mc_webch"). NULL for non-webchat rows and for
-- pre-fix webchat rows whose channel can no longer be reconstructed (those remain
-- visible across channels until backfilled by an ops script).
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS channel_id BIGINT;
