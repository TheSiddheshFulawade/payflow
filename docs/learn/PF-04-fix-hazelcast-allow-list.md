# Fix (PF-04 code, found in PF-07's CI): an error a member could not read stopped a cluster join

Code: `risk-service/src/main/java/com/siddheshfulawade/payflow/risk/infra/HazelcastConfigFactory.java:111-130` and
`risk-service/src/main/resources/hazelcast-allowed-errors.txt`. Requirement: REQ-PF-032. Background: the
"Java deserialization filter" section of [PF-04-risk.md](PF-04-risk.md).
Status: local `./mvnw -o -pl risk-service -am verify` green (127 tests, 0 failures). CI has not run it yet.

## What went wrong, in plain words

Members send each other errors as Java objects. Our deserialization allow-list (exact class names, no prefixes) only
named the few Hazelcast errors a velocity check expects. During a join, the partition migration got back a
`PartitionStateVersionMismatchException` ("your partition table is older, try again"). The receiving member's filter
refused to read it, so that reply was dropped: the migration was never answered and the cluster stayed "not safe" for
30 s. `HazelcastMemberTest.joiningMemberIsHandedOutOnlyWhenClusterIsSafe` (line 121) failed on ubuntu JDK 21 in CI.

Lesson: an allow-list of what *your* code sends is not enough. The library sends its own internal messages, and an
unreadable error does not fail loudly; it leaves an operation hanging.

## The fix

- Every concrete Hazelcast `Throwable` that goes by Java serialization (not `DataSerializable`, not under `shaded/`),
  plus its base classes up to `Throwable`: 112 errors, 124 names. Still exact names, read from a file
  (`HazelcastConfigFactory.java:120`).
- The file is **generated** by a jar scan in a test, not hand-picked. Two tests pin it both ways:
  the file equals the scan (`HazelcastSerializationFilterTest.java:188`), and the whole whitelist equals the explicit
  entries plus the scan (line 196). A Hazelcast upgrade that adds an error, or anyone who adds a class by hand,
  fails the build.
- Missing or empty file: the member does not start (`HazelcastConfigFactory.java:137`). Fail closed.
- Real round trips through the filter: `PartitionStateVersionMismatchException`, `MemberLeftException` with its
  member, and an error with a cause (`HazelcastFailureTest.java:155-190`).

Rejected: the prefix `com.hazelcast.` would open every Serializable class in the jar, each one a possible gadget.

## Five interview questions

1. **Why exact class names and not a package prefix?** A prefix lets any Serializable class under it be created from
   a crafted stream, including ones you never use; those are the gadgets in a deserialization attack. Exact names
   open only what you listed.
2. **How did one refused class stop a whole cluster join?** The error was a *reply*. When the reply cannot be read it
   is dropped, so the sender of the migration waits for an answer that never comes. A filter that fails silently
   turns a "try again" into a hang.
3. **Why generate the list instead of hand-picking?** Hand-picking is what missed the error. A scan is complete for
   the jar you ship, and a test that compares file and scan catches both a library upgrade and a hand edit.
4. **Does this make the member port safe?** No. The port has no authentication in Hazelcast Community; the network
   is the control. The filter is a second layer, and it covers Java serialization only.
5. **What does `MemberLeftException` add to the list, and why care?** It writes the member's version
   (`MemberVersion`), so that class is listed too. Its `readObject` rebuilds the address from the host string;
   members are configured by IP, so no DNS lookup happens while reading an error.

## TODO-ME

None for this fix: the open TODO-ME for risk-service is in `RiskLimitsEdgeTest` (PF-04).
