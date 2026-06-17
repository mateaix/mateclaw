-- RFC-063r §2.12: persist ChatOrigin Memento snapshot on approval (MySQL dialect).

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_tool_approval' AND column_name = 'chat_origin'
    ) THEN
        ALTER TABLE mate_tool_approval ADD COLUMN chat_origin TEXT;
    END IF;
END $$;
