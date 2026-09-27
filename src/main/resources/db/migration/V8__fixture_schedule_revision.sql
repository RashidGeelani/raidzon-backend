ALTER TABLE tournament_fixtures ADD COLUMN schedule_revision integer NOT NULL DEFAULT 0 CHECK (schedule_revision >= 0);
