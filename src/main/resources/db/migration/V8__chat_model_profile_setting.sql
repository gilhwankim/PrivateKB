CREATE TABLE application_setting (
    setting_key varchar(120) PRIMARY KEY,
    setting_value varchar(500) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO application_setting (setting_key, setting_value)
VALUES ('chat.profile', 'DISABLED')
ON CONFLICT (setting_key) DO NOTHING;
