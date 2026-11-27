# Keycloak realm `payflow`

Imported on start from `payflow-realm.json`.

- Realm roles: `payments:write`, `payments:read`, `ops`.
- Public client `payflow-test` (password grant only, no browser flow). This public password-grant client is demo-only; production would use the authorization code flow with PKCE, or client credentials for services. Realm roles appear in the access token under `realm_access.roles`.
- Users: `alice` and `bob` (`payments:write`, `payments:read`), `ops` (`ops`). The users have fixed ids (`alice` `e45bda66-092a-4922-b8e0-b14c7b03b479`, `bob` `ffbff25f-7df1-4acc-a432-40ec070024f7`, `ops` `f13cf402-e7fc-477b-ac63-a2051b6da9bd`), so wallets seeded for them survive a Keycloak reset. Access token lifespan is 300 seconds. Brute-force protection locks an account for a while after 5 failed logins. Access tokens carry the audience `payflow-api` (checked by the services from PF-07); `offline_access` is not offered, so there are no long-lived offline tokens.

All passwords (each user's password equals its user name; the admin console login is `admin`/`admin`) are a
LOCAL DEMO VALUE and are never reused anywhere real. JSON has no comments, so the marker lives here.

Get a token:

```sh
curl -s -d grant_type=password -d client_id=payflow-test -d username=alice -d password=alice \
  http://localhost:8180/realms/payflow/protocol/openid-connect/token
```
