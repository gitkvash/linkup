CREATE TABLE Activities (
    activity_id UUID PRIMARY KEY,
    creator_id UUID,
    activity_type VARCHAR(30) NOT NULL,
    title VARCHAR(255) NOT NULL,
    visibility VARCHAR(20) NOT NULL,
    start_time TIMESTAMPTZ,
    end_time TIMESTAMPTZ,
    FOREIGN KEY (creator_id) REFERENCES Users(user_id)
);

CREATE INDEX idx_activities_creator ON Activities(creator_id);
CREATE INDEX idx_activities_start_time ON Activities(start_time);

CREATE TABLE Locations (
    location_id UUID PRIMARY KEY,
    activity_id UUID UNIQUE,
    geom_point GEOMETRY,
    address_text VARCHAR(255),
    FOREIGN KEY (activity_id) REFERENCES Activities(activity_id)
);

CREATE TABLE Participants (
    activity_id UUID,
    user_id UUID,
    status VARCHAR(20) NOT NULL,
    PRIMARY KEY (activity_id, user_id),
    FOREIGN KEY (activity_id) REFERENCES Activities(activity_id),
    FOREIGN KEY (user_id) REFERENCES Users(user_id)
);

CREATE INDEX idx_participants_user ON Participants(user_id);
