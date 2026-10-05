-- The organizer can delete a match before anything is recorded or while it is paused
-- (e.g. created by mistake or abandoned).
-- The row is kept so retries from an old phone are refused, but it is hidden everywhere: like an
-- admin removal (removed_at is also set) it leaves stats, public pages and its tournament fixture,
-- and unlike one it also disappears from the organizer's own match list and can't be scored.
ALTER TABLE matches ADD COLUMN deleted_at timestamptz;
