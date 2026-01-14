CREATE TABLE IF NOT EXISTS message_token_index (
    token TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    node_index INTEGER NOT NULL,
    node_id TEXT NOT NULL,
    PRIMARY KEY (token, conversation_id, node_index)
);
CREATE INDEX IF NOT EXISTS idx_token_conv ON message_token_index(conversation_id, token);
