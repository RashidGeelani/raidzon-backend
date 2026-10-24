-- Adding someone to a team by phone is an invitation until the person with that phone accepts.
-- Before acceptance the team sees only the name the organizer typed (never the player's own
-- name or profile), and invited staff have no rights. Rows from before invitations count as accepted.
ALTER TABLE team_members ADD COLUMN accepted_at timestamptz;
UPDATE team_members SET accepted_at = joined_at;
ALTER TABLE team_staff ADD COLUMN accepted_at timestamptz;
UPDATE team_staff SET accepted_at = added_at;

-- The app refers to staff by this ID instead of the global player profile ID.
ALTER TABLE team_staff ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid();
CREATE UNIQUE INDEX team_staff_id ON team_staff(id);

ALTER TABLE notifications DROP CONSTRAINT notifications_kind_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_kind_check CHECK (kind IN (
    'JOIN_REQUEST_RECEIVED', 'JOIN_REQUEST_WITHDRAWN', 'JOIN_REQUEST_APPROVED', 'JOIN_REQUEST_REJECTED', 'MATCH_REMOVED',
    'TEAM_INVITE', 'TEAM_INVITE_DECLINED'));
