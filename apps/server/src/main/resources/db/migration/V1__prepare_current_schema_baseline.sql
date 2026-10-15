-- PostgreSQL restore prerequisite for the reviewed current-state schema baseline.
-- Functions are created before some referenced tables; PostgreSQL must defer body validation.
SET check_function_bodies = false;
