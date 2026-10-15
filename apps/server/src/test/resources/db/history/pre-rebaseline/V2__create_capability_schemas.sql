-- Persistence ownership convention:
-- platform/bootstrap migrations may establish shared physical topology only.
-- Capability-owned tables belong in later capability-focused migrations.

CREATE SCHEMA identity;
CREATE SCHEMA catalog;
CREATE SCHEMA access;
CREATE SCHEMA governance;
CREATE SCHEMA credential;
CREATE SCHEMA integration;
CREATE SCHEMA administration;
CREATE SCHEMA audit;
CREATE SCHEMA platform;
