-- Allow awaiting_approval (17 characters) and future task states.
ALTER TABLE mate_team_task MODIFY COLUMN status VARCHAR(32) DEFAULT 'pending';
