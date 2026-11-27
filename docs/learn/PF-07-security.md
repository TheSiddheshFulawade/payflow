# PF-07 Security: who may call, and whose money it is

Status: PF-07 work on branch `feat/pf-07-security`, with the security follow-up (metadata path closed, one subject rule
in all three services, one method per wallet ops route). Maven `verify` was run per module offline; see the session
handoff for the counts. Payment-service paths are shortened to `payment-service/.../api/` (full package
`com/siddheshfulawade/payflow/payment/api`); the same for wallet and risk. Line numbers are from the working tree and
may move when the code is edited.

## What the code does, in plain words

Before PF-07 anyone could call the APIs. Now all three services (payment, wallet, risk) check a signed token on every
request, and two services also check whose money is involved.

1. **Every request needs a valid token.** Only `GET /actuator/health` is open
   (`payment-service/.../api/SecurityConfig.java:41`). The token is a JWT issued by Keycloak.
2. **The token must pass these checks**: a valid signature, the right issuer, not expired, and the audience
   `payflow-api` (`TokenRules.java:30-38`). Boot checks the signature, the issuer and, when the token has an `exp`, that it has not
   expired; "`exp` must be present", the audience and the subject rule are ours.
3. **The role decides the action.** `payments:write` creates a payment, `payments:read` reads one
   (`SecurityConfig.java:42-45`); `ops` seeds and freezes wallets (wallet-service `SecurityConfig.java`).
4. **Everything else is refused**: `anyRequest().denyAll()` (`SecurityConfig.java:46-48`).
5. **The caller is the token's `sub`**, never a header or body field (`JwtClientIdentity.java`, `clientId()`).
6. **You see only your own things.** A client reads only the payments it created (`sub` = `client_id`), as
   REQ-PF-003 says: payment-service does not know wallet owners, and the wallet's owner check makes creator = payer
   owner for every payment that held money. Someone else's payment gives the same 404 as an unknown id
   (`PaymentController.java`, `read`, the `filter` on `clientId`). Same for a wallet (`WalletController.java`, `read`
   and `mayRead`; `ops` may read any).
7. **The wallet checks that the payer wallet belongs to the requester** before it holds any money
   (`WalletCommandHandler.java`, `refusalToReserve`). The requester travels inside the `ReserveFunds` command
   (`ReserveFunds.java`), and payment-service fills it from the payment's `clientId`
   (`SagaStateMachine.java:128`).

## Core concepts

### Resource server vs authorisation server
Keycloak is the **authorisation server**: it checks the password and *issues* tokens. Our three services are
**resource servers**: they never see a password, they only *verify* the token they are shown. Think of Keycloak as the
passport office and our services as the border desk. The border desk does not phone the passport office on each
request: it downloads the office's public keys once (the **JWKS**, a published list of public keys) and checks the
signature itself. In `application.yml` this is one line, `issuer-uri: ${PAYFLOW_ISSUER_URI}`. Boot reads the keys
lazily on the first token, so the service and its health check start even when Keycloak is down.

### What "validating a JWT" means here
- **Signature** against the JWKS: proves Keycloak made it and nobody edited it.
- **Issuer**: the token says who issued it; it must be our realm.
- **Expiry (`exp`) required**: the standard Spring check *skips* a token that has no `exp`, so such a token would
  live forever. `TokenRules.java:30` refuses it.
- **Audience `payflow-api`** (`TokenRules.java:33`): the token must be meant for our API. We use a custom validator
  bean (`SecurityConfig.java:74-77`; Boot adds every `OAuth2TokenValidator<Jwt>` bean to its decoder) instead of the
  `audiences` property, because that property does nothing when it is empty, so a config slip would quietly turn the
  check off. The audience is shared by the three services, so it keeps *foreign* tokens out; it does not separate
  payment from wallet (roles do that). That is a known limit.
- **Subject** (`TokenRules.java:36`, in all three services): see "One subject rule" below.

### Roles from `realm_access.roles`, no `ROLE_` prefix
Keycloak puts realm roles in `realm_access.roles`. `RealmRoles.java:19-27` turns each string there into an authority
with exactly that name (`payments:write`). Spring's default would read `scope` and add prefixes, and then
`hasAuthority("payments:write")` would never match. A token with no `realm_access`, a malformed one, or a non-string
entry gets no authority from it: 403, never a 500. Roles in a top-level claim or in `scope` grant nothing
(`JwtValidationTest.java:101`).

### The metadata path is closed
Spring Security 7 adds `OAuth2ProtectedResourceMetadataFilter` to every resource server, before authorization, and the
configurer has no switch to turn it off. Left alone it would answer `/.well-known/oauth-protected-resource` to anyone.
Each `SecurityConfig` removes it from the built chain, so that path is just another unknown path: 401 without a token.
It is the same in the three services.

### Deny by default
`anyRequest().denyAll()` is the last rule. Why: an earlier draft had rules only for `GET` on the payment path. A `HEAD`
request is served by Spring MVC's GET handler but did not match the GET-only rule, so it slipped past the rules. With
`denyAll()`, anything not named gets 401 (no token) or 403 (a token). Tests: `SecurityHardeningTest.java:81` (HEAD),
`:100` and `:111` (other methods and paths). Rule of thumb: write what is allowed, and make "everything else" a
refusal, so a new endpoint is closed until someone opens it.

### 401 vs 403, as problem details
- **401 Unauthorized** = "I do not know who you are": no token, or a bad, expired or forged one.
- **403 Forbidden** = "I know who you are, and you may not do this": valid token, missing role.

Both are RFC 9457 problem details (`application/problem+json`) with a fixed `type` and `title`
(`SecurityProblems.java`, `write`). The `WWW-Authenticate` header is exactly `Bearer` (no token) or
`Bearer error="invalid_token"` (bad token) (`SecurityProblems.java:41`). It deliberately has **no
`error_description`**: that text would say things like "wrong issuer" or name the audience, which helps someone
probing the setup. The detail is logged at DEBUG only. These bodies are written by hand because the security filter
runs *before* Spring MVC, so our normal `@RestControllerAdvice` never sees these errors.

### Firewall `RequestRejectedException` becomes 400
Spring Security's firewall refuses odd requests (a NUL byte in a header, a path with `;` or encoded dots) by throwing
`RequestRejectedException`. The default is an empty 400 or a 500. The same `SecurityProblems` bean implements
`RequestRejectedHandler` (`SecurityProblems.java:54`) and gives a 400 problem. The comment at
`SecurityConfig.java:63-67` names the trap: a second bean of that type makes Spring's lookup ambiguous and silently
brings back the default. The advice classes also handle the exception, for the case where the header is first read
inside the controller call (`ApiProblems.java`, `rejectedByFirewall`).

### Client id = JWT `sub`
`JwtClientIdentity` reads the subject of the verified token. Before PF-07 a local stand-in supplied it
(`LocalClientIdentity`, now deleted). The subject scopes the `Idempotency-Key` (PF-05), so two customers using the key
`abc` do not collide, and nobody can claim another's payment by writing a header.

### No existence leak: foreign = unknown
If Bob asks for Alice's payment, the answer is the same 404 as for a random id: same status, same body. We even fix
the `instance` field (`ApiProblems.java`, `notFound`) because Spring would otherwise fill in the request path, which
repeats the id the caller typed. A 403 would say "this id exists and is not yours", which is information.

### The owner check in the wallet
Hiding things at the API is not enough: the payer is a field in the request body, so Bob could *create* a payment with
Alice's wallet as payer. Money is held by wallet-service, which knows who owns each wallet (`owner_sub`). So:
- payment-service puts the creator's `sub` in the command as `requester`;
- wallet-service compares it with the wallet's owner (`WalletCommandHandler.java`, the `OWNER_MISMATCH` branch) and
  replies `FundsRejected(OWNER_MISMATCH)`; the payment ends `REJECTED`.

Details that matter, each with a test in `wallet-service/.../app/OwnerCheckTest.java`:
- **Order**: after `WALLET_UNKNOWN` (no row, so no owner to compare), but **before** frozen and funds. A stranger must
  not learn that a wallet is frozen or poor (`mismatchDoesNotRevealTheBalance`, line 74).
- **Under the row lock** (`wallets.lock(...)` comes first). The owner never changes after seeding, and the lock means
  the check and the later update see the same row.
- **Nothing is held** on a mismatch; the decision is stored as a REJECTED hold.
- **Idempotent**: a redelivered or re-sent command gets the stored REJECTED answer; the real owner re-using the same
  payment id cannot revive it (`ownerCannotReviveARejectedPayment`, line 126). Same idea as the inbox from PF-02.
- **Exact comparison**: case or whitespace differences do not match (`comparisonIsExact`).

Why not let payment-service read wallet owners? The services share only Kafka messages; a cross-service read would
couple them and could race with changes.

### One subject rule
`Subjects.isValid` (`contracts/.../Subjects.java`): 1 to 64 printable ASCII characters (`[\x21-\x7E]`). It is used by
the token validator of all three services (`TokenRules`), the `ReserveFunds` constructor (`Checks.subject`) and the wallet's `ownerSub` request field
(`CreateWalletRequest.java`). One class, so the places cannot drift apart. Keycloak subjects are 36-character UUIDs;
the rule keeps control characters, line breaks, spaces and invisible characters out of logs and messages (log
forging) and out of a field we compare for ownership. It replaced a looser 255-character rule in the wallet.

### No default issuer
`issuer-uri: ${PAYFLOW_ISSUER_URI}` has no `:default` part. If the operator forgets it, the service fails to start,
instead of trusting a made-up issuer. `ProductionIssuerConfigTest` reads the real `application.yml` to pin this.

### Fixed Keycloak user ids
The realm JSON gives `alice`, `bob` and `ops` fixed ids (`deploy/keycloak/payflow-realm.json`, listed in
`deploy/keycloak/README.md`). A wallet's owner is a token `sub`, which Keycloak would otherwise invent at import time.
Without fixed ids, a Keycloak reset would give Alice a new `sub` and her seeded wallet would belong to nobody.

## The contract break (read this one twice)
`ReserveFunds` gained a required field, `requester`, but the envelope version stays 1. Accepted because nothing is
released yet. Consequences:
- A `ReserveFunds` already in Kafka without `requester` is **poison** for the new wallet: decoding fails
  (`ReserveFundsRequesterTest.java:47`), it goes to the dead-letter topic (PF-02) and is never an unchecked reserve.
  The payment's deadline then fires and the sweeper (PF-06) re-sends a command rebuilt from the payment row, now with
  the requester, up to 3 times; after that it releases, and the payment ends `REVERSED` with nothing held.
- **Deploy wallet-service first**, so that no new command meets an old wallet that has no check.
- After v1.0.0 a new required field means version 2, and a window where consumers read both versions.

## Message flow: owner check

```
Bob          PaymentController   Payment saga     Outbox -> Kafka     WalletCommandHandler    wallet database
 | POST payer=alice-wallet (JWT sub=bob)                                                           |
 |------------->| filter chain: signature, issuer, exp, audience, role payments:write -> ok        |
 |              | clientId = sub = bob                                                             |
 |              |------------>| RESERVING; outbox ReserveFunds(payer=alice-wallet, requester=bob)  |
 |<-- 202 ------|             |-------------->| wallet.commands ------>|                          |
 |                                                                     | lock wallet row -------->|
 |                                                                     | owner_sub = alice != bob |
 |                                                                     | store REJECTED hold + reply (one tx)
 |                            |<-------- FundsRejected(OWNER_MISMATCH) -|                         |
 |                            | RESERVING -> REJECTED (nothing was held, nothing to release)
 | GET /payments/{id} as bob   -> status REJECTED
 | GET /payments/{id} as carol -> 404, same body as an unknown id
```

## Interview questions

**1. What is the difference between a resource server and an authorisation server, and how does our service check a
token without calling Keycloak on every request?**
Keycloak (authorisation server) logs people in and issues signed JWTs. Our services (resource servers) only verify
them. They fetch Keycloak's public keys (JWKS) from the issuer, cache them, and verify each token's signature locally,
then check issuer, expiry and audience. So a brief Keycloak outage does not stop requests with tokens signed by a key
we already hold. The cost: a token cannot be revoked before it expires, which is why access tokens are short (300 s in
our realm).

**2. Why `anyRequest().denyAll()`?**
An allow-list should end in a refusal. A HEAD request is served by the GET handler in Spring MVC but did not match our
GET-only rule, so it slipped past the rules in an earlier draft. With `denyAll()` any method or path that no rule names
is 401 or 403, and a new endpoint stays closed until someone deliberately opens it. It is tested with HEAD, DELETE and
unknown paths.

**3. When do you return 401 and when 403, and why does `WWW-Authenticate` carry no `error_description`?**
401: not authenticated (no, bad or expired token); we send `Bearer` or `Bearer error="invalid_token"`. 403:
authenticated but missing the role. The description would reveal why a token failed (wrong issuer, wrong audience,
decoder text), which helps an attacker adjust a forged token; the detail goes to the DEBUG log. Both are RFC 9457
problem bodies written by hand, because the security filter runs before Spring MVC's error advice.

**4. Bob asks for Alice's payment. What does he get, and why? And if he tries to pay from Alice's wallet?**
The same 404 as for an unknown id, with an identical body (even `instance` is fixed). A 403 would confirm the id
exists. Paying from her wallet is checked in the wallet service, which owns `owner_sub`: it compares the `requester`
in `ReserveFunds` under the row lock, replies `OWNER_MISMATCH` before it looks at frozen or funds, and holds nothing.

**5. You added a required field to a Kafka message and kept version 1. Defend or attack that.**
Defend: nothing is released, so there are no old readers to protect, and a message without the field is poison
(dead-letter), never an unchecked reserve, which is the safe failure. After the deadline the sweeper re-sends a command
rebuilt from the payment row (with the requester) up to 3 times, then releases: the payment ends REVERSED. Deploy the consumer (wallet) first. Attack: in production this breaks in-flight messages, so after v1.0.0 a
new required field must be version 2, with consumers reading both versions during the roll-out; an optional field with
a safe default would not need that.

## What breaks if...

In `WalletCommandHandler.refusalToReserve`, imagine moving the owner check to the *end*, after
`payer.refusesReserve(...)`. Which tests in `OwnerCheckTest` go red (start with line 74), and what does a stranger now
learn? Then imagine removing the `lock` call before the check: the owner check itself would still be right, because the
owner never changes, so which other invariant (no overspending) breaks? Reason it through first; then check on a
scratch branch with `mvnw.cmd -q -B clean test -pl wallet-service -am`, and revert.

## TODO-ME

`payment-service/src/test/java/com/siddheshfulawade/payflow/payment/api/RealmRolesTest.java`: a disabled unit test that
a non-string entry in `realm_access.roles` grants nothing and does not crash. What to do and how to check it are in the
comment above the test (marked `// TODO-ME:`). It is `@Disabled`, so the build stays green while it is unfinished. This
is the only TODO-ME of PF-07.
