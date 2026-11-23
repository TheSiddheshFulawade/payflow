# Keycloak realm `payflow`

Imported on start from `payflow-realm.json`.

- Realm roles: `payments:write`, `payments:read`, `ops`.
- Public client `payflow-test` (password grant only, no browser flow). Realm roles appear in the access token under `realm_access.roles`.
- Users: `alice` and `bob` (`payments:write`, `payments:read`), `ops` (`ops`). Access token lifespan is 300 seconds. Brute-force protection locks an account for a while after 5 failed logins. Access tokens carry the audience `payflow-api` (checked by the services from PF-07); `offline_access` is not offered, so there are no long-lived offline tokens.

All passwords (each user's password equals its user name; the admin console login is `admin`/`admin`) are a
LOCAL DEMO VALUE and are never reused anywhere real. JSON has no comments, so the marker lives here.

Get a token:

```sh
curl -s -d grant_type=password -d client_id=payflow-test -d username=alice -d password=alice \
  http://localhost:8180/realms/payflow/protocol/openid-connect/token
```
