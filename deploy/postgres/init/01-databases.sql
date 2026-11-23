-- One database and one login per service; a service never reads another service's tables.
-- LOCAL DEMO VALUE - each password equals its user name; never reuse anywhere real.

CREATE USER payment WITH PASSWORD 'payment'; -- LOCAL DEMO VALUE
CREATE USER wallet WITH PASSWORD 'wallet'; -- LOCAL DEMO VALUE
CREATE USER risk WITH PASSWORD 'risk'; -- LOCAL DEMO VALUE
CREATE USER recon WITH PASSWORD 'recon'; -- LOCAL DEMO VALUE

CREATE DATABASE payment OWNER payment;
CREATE DATABASE wallet OWNER wallet;
CREATE DATABASE risk OWNER risk;
CREATE DATABASE recon OWNER recon;

-- Invariant: a service user can connect only to its own database (PUBLIC can connect by default).
REVOKE CONNECT ON DATABASE payment FROM PUBLIC;
REVOKE CONNECT ON DATABASE wallet FROM PUBLIC;
REVOKE CONNECT ON DATABASE risk FROM PUBLIC;
REVOKE CONNECT ON DATABASE recon FROM PUBLIC;
-- The maintenance database too, so a service user cannot log in anywhere but its own database.
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
