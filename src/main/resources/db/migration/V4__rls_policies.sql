ALTER TABLE activities ENABLE ROW LEVEL SECURITY;

CREATE POLICY activity_select_policy ON activities FOR SELECT USING (
    creator_id = current_setting('app.current_user_id', true)::uuid
    OR visibility = 'PUBLIC'
    OR EXISTS (
        SELECT 1 FROM participants p 
        WHERE p.activity_id = activities.activity_id 
        AND p.user_id = current_setting('app.current_user_id', true)::uuid
    )
    OR (visibility = 'FRIENDS' AND EXISTS (
        SELECT 1 FROM friendships f 
        WHERE f.status = 'ACCEPTED' 
        AND (
            (f.user_a_id = activities.creator_id AND f.user_b_id = current_setting('app.current_user_id', true)::uuid) 
            OR 
            (f.user_b_id = activities.creator_id AND f.user_a_id = current_setting('app.current_user_id', true)::uuid)
        )
    ))
);

CREATE POLICY activity_insert_policy ON activities FOR INSERT WITH CHECK (
    creator_id = current_setting('app.current_user_id', true)::uuid
);

CREATE POLICY activity_update_policy ON activities FOR UPDATE USING (
    creator_id = current_setting('app.current_user_id', true)::uuid
);

CREATE POLICY activity_delete_policy ON activities FOR DELETE USING (
    creator_id = current_setting('app.current_user_id', true)::uuid
);
