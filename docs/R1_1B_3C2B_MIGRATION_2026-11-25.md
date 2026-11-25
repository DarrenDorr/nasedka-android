# R1.1b-3c-2b-2a — безопасная миграция известных SQLite v1
25.11.2026. Baseline ba7bf55f0f521c7ff248f33c75c93bd6572ec2c9; -3c-2b-1 97/97 verified ([evidence](../security/evidence/R1_1B_3C2B1_FINAL_2026-11-25.json)). User authorized continuation. NOT_RUN.

## Implementation
RestoreSchemaMigration is isolated startup-only, under the existing exclusive recovery gate, no pending restore evidence and no concurrent DB handles. Inspect both files before first migration; prepareClosed/checkpoint can alter normal journal metadata, not a byte-for-byte immutable audit. Check user_version 1 or 2, integrity_check, table set and PRAGMA table_info name/type/notnull/default/PK-position, compare to freshly constructed v2 reference with known v1 omissions. Column order ignored intentionally because ALTER appends. Reject unexpected views/triggers and table/column structure. Ignore SQLite-owned sqlite_* and android_metadata, indexes not compared; not complete SQL/constraint/foreign-key or historical origin proof. Current onUpgrade methods already defined: incubation adds profile_id default nesushka, farm adds hatched_at default 0 and flock_notes/index. Use original methods in transaction, validate resulting structure before commit; reopen/prepareClosed and verify v2. No CREATE flag on live file; non-destructive handler. Each migration atomic per SQLite DB, not a multi-DB transaction. Pair remains inaccessible until both v2 and final BackupStore.relations succeed. Restart of known mixed v1/v2 versions resumes second; no downgrade. If final relation validation fails, already successful schema migrations remain, business access blocked, no automatic rollback of schema. Existing v1 partner preflight before creating missing partner. Unknown versions/structures blocked without replacement. Fresh staging policy remains unchanged.
 
## Test provenance
Reuse exact farm v1 fixture from archived commit 535417a2be877491d40b7b3cfaa6915c8eaf0374 and reconstructed incubation v1 fixture from BackupReliabilityTest; incubation fixture is NOT archival APK. Change helper visibility package-private test-only. Five new Android tests:
- actualV1PairMigratesWithoutLosingIdsAndLinks
- mixedSchemaAfterFirstMigrationResumesSecond
- historicalV1PartialPairMigratesAndCreatesMissingPartner
- unexpectedPartnerSchemaBlocksBeforeFirstMigration
- unknownSchemaVersionBlocksWithoutRecreation
Actual synthetic files; mixed version is modeled by completing one migration, not a physical process kill. CI exact startup test count now 13. Total 41 JVM + 53 Android + 6 process cases + 2 ENOSPC = 102. NOT_RUN. 

## Remaining blockers
Legacy remnants durable classification and interrupted initialization disposition, independent process restart after simulated ambiguous prefs commit, PENDING durability review and full startup audit. User data may contain legitimate legacy variants outside known shapes or relation errors; block and diagnose, never silently overwrite. No Application/helper/runtime wiring; still R1-F01/F02 OPEN, PR #8 Draft. No release or merge until joint -3/-4. Approved design/public JSON v1 unchanged; actual device/archival APK upgrade and physical power loss not proved.
