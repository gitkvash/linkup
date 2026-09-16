CREATE TABLE Users (
    user_id UUID PRIMARY KEY,
    username VARCHAR(50) UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_users_username ON Users(username);

CREATE TABLE Friendships (
    user_a_id UUID,
    user_b_id UUID,
    status VARCHAR(20) NOT NULL,
    PRIMARY KEY (user_a_id, user_b_id),
    FOREIGN KEY (user_a_id) REFERENCES Users(user_id),
    FOREIGN KEY (user_b_id) REFERENCES Users(user_id)
);

CREATE INDEX idx_friendships_user_a ON Friendships(user_a_id);
CREATE INDEX idx_friendships_user_b ON Friendships(user_b_id);

CREATE TABLE Groups (
    group_id UUID PRIMARY KEY,
    owner_id UUID,
    group_name VARCHAR(100) NOT NULL,
    FOREIGN KEY (owner_id) REFERENCES Users(user_id)
);

CREATE INDEX idx_groups_owner ON Groups(owner_id);

CREATE TABLE Group_Members (
    group_id UUID,
    user_id UUID,
    joined_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (group_id, user_id),
    FOREIGN KEY (group_id) REFERENCES Groups(group_id),
    FOREIGN KEY (user_id) REFERENCES Users(user_id)
);

CREATE INDEX idx_group_members_group ON Group_Members(group_id);
CREATE INDEX idx_group_members_user ON Group_Members(user_id);
