-- Current Wyrmgate IAM pre-release schema baseline.
-- Capability schemas are physical organization only; capability ownership remains semantic.

CREATE SCHEMA access;

CREATE SCHEMA administration;

CREATE SCHEMA audit;

CREATE SCHEMA catalog;

CREATE SCHEMA credential;

COMMENT ON SCHEMA credential IS 'Credential capability authoritative metadata and durable rotation process state.';

CREATE SCHEMA governance;

CREATE SCHEMA identity;

CREATE SCHEMA integration;

CREATE SCHEMA platform;
