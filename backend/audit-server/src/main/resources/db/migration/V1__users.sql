-- People who can log in. Roles: admin, owner, contributor, viewer.
CREATE TABLE app_user (
    name        VARCHAR(81) PRIMARY KEY,
    role        VARCHAR(16) NOT NULL,
    disabled    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    last_login  TIMESTAMP WITH TIME ZONE
);
