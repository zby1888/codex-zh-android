PRAGMA foreign_keys = ON;
CREATE TABLE provider_profiles (
  id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, protocol TEXT NOT NULL,
  base_url TEXT NOT NULL, model_id TEXT NOT NULL, secret_ref TEXT,
  headers_json TEXT NOT NULL DEFAULT '{}', enabled INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE mcp_servers (
  id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, transport TEXT NOT NULL,
  config_json TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE mcp_tool_policies (
  server_id TEXT NOT NULL, tool_name TEXT NOT NULL, policy TEXT NOT NULL DEFAULT 'ask',
  updated_at INTEGER NOT NULL, PRIMARY KEY (server_id, tool_name),
  FOREIGN KEY (server_id) REFERENCES mcp_servers(id) ON DELETE CASCADE
);
CREATE TABLE chat_sessions (
  id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, mode TEXT NOT NULL,
  provider_id TEXT, codex_thread_id TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE approval_events (
  id TEXT PRIMARY KEY NOT NULL, action_type TEXT NOT NULL, target TEXT NOT NULL,
  decision TEXT NOT NULL, metadata_redacted TEXT NOT NULL DEFAULT '{}', created_at INTEGER NOT NULL
);
