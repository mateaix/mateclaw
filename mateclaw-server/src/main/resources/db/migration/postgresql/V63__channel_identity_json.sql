-- RFC-084 follow-up: persist the identity returned by ChannelVerifier so
-- the channel list can show "Connected as @MyBot" instead of generic
-- type-level descriptions. Populated on wizard create from VerificationResult;
-- refreshed by adapters on first successful connect.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_channel' AND column_name = 'identity_json'
    ) THEN
        ALTER TABLE mate_channel ADD COLUMN identity_json JSONB;
    END IF;
END $$;
