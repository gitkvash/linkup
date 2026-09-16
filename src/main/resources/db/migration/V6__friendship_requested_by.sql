ALTER TABLE friendships ADD COLUMN requested_by UUID REFERENCES users(user_id);
