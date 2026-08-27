# Cloudberry SQLancer Provider — Design

Date: 2026-08-13

## Goal
Add a SQLancer `cloudberry` DBMS provider that tests Apache Cloudberry
(a PostgreSQL-based MPP database) through its distributed and
columnar features, modeled on the existing `citus` provider (Postgres-kernel +
distribution extension). Doris is only a conceptual reference for how a
distributed DB models distribution/storage — not a code template (Doris is a
from-scratch, non-PG provider).

## Key decisions
- **Architecture:** extend the `postgres` provider (citus-style). Reuse Postgres
  AST, expression generator, and NoREC/TLP oracles. Auto-registered via
  `@AutoService(DatabaseProvider.class)`.
- **Connection:** coordinator-only. Cloudberry is transparent MPP, so unlike
  Citus there is **no worker-node registration and no `CREATE EXTENSION`** —
  `createDatabase` is inherited from `PostgresProvider` unchanged.
- **v1 features:** distribution policy + storage format. No GP partitioning (v2).
- **Oracles:** NoREC + TLP (Where / Having / Aggregate).

## Verified facts (live cluster, coordinator port 7000)
- Access methods registered in `pg_am` (amtype='t'): `heap, ao_row, ao_column, pax`.
  `CREATE TABLE ... USING <am>` works for all four. `PostgresGlobalState`
  already reads `pg_am` at startup, so `getRandomTableAccessMethod()` **already
  yields storage-format variety for free** — no table-generator change needed.
- `ALTER TABLE t SET DISTRIBUTED BY (col) | RANDOMLY | REPLICATED` all work.
- Distribution-key rule: `DISTRIBUTED BY` columns must be a **subset of PRIMARY
  KEY / UNIQUE** columns, else `ERROR: PRIMARY KEY and DISTRIBUTED BY definitions
  are incompatible`. Same constraint-aware column selection as Citus.
- Read policy back from `gp_distribution_policy` (`policytype` p=hash/r=replicated,
  `distkey`=attnums) and storage from `pg_class.relam → pg_am.amname`.

## Components (v1, ~11 files under src/sqlancer/cloudberry/)
- `CloudberryProvider extends PostgresProvider` — override `getDBMSName()`,
  `createTables()` (use CloudberryTableGenerator), `generateDatabase()` (add the
  distribution step), `getTestOracle()`. Inherit `createDatabase()`,
  `prepareTables()`, `readFunctions()`, and the Postgres Action set (TABLESPACE
  action self-neutralizes when tablespaces are disabled).
- `CloudberryGlobalState extends PostgresGlobalState` — override `readSchema()`.
- `CloudberrySchema extends PostgresSchema` — `CloudberryTable` carries
  distribution columns, policy type, and access method; `fromConnection` joins
  `gp_distribution_policy` and `pg_am`.
- `CloudberryOptions extends PostgresOptions` — `--cloudberry-oracle`; override
  `isTestTablespaces()` to honor the flag only (default OFF), fixing the upstream
  `testTablespaces || OSdefault` bug that makes `--test-tablespaces false` a no-op
  and floods `CREATE TABLESPACE`.
- `CloudberryOracleFactory` — NOREC / WHERE / HAVING / QUERY_PARTITIONING, reusing
  Postgres oracles + `CloudberryCommon` expected errors.
- `CloudberryBugs` — feature flags for known CB bugs (mirrors `CitusBugs`).
- `gen/CloudberryCommon` — expected-errors list (the main iterative effort).
- `gen/CloudberryTableGenerator extends PostgresTableGenerator` — inject CB errors.
- `oracle/tlp/CloudberryTLPBase`, `CloudberryTLPAggregateOracle`,
  `CloudberryTLPHavingOracle` — mirror the Citus TLP wrappers.

## Data flow: generateDatabase()
1. `readFunctions()`
2. `createTables(4–6)` — vanilla PostgresTableGenerator (random storage AM,
   Cloudberry auto-assigns a default distribution key).
3. For each non-temporary table, with probability, **reset distribution**:
   - inspect PK/UNIQUE/EXCLUDE constraints (Citus information_schema queries);
   - if constrained, eligible distribution columns = intersection of constraint
     columns → `ALTER TABLE SET DISTRIBUTED BY (col)`; if none eligible, keep the
     default distribution (a constrained table cannot be RANDOMLY/REPLICATED);
   - if unconstrained → random `BY(col)` / `RANDOMLY` / `REPLICATED`.
4. `updateSchema()` → `prepareTables()` → `SET statement_timeout`.

Distribution injection uses post-create `ALTER ... SET DISTRIBUTED` (Option A:
zero table-generator change, empty table so cheap) rather than injecting
`DISTRIBUTED BY` into `CREATE TABLE` (Option B: rewrites the static generator).

## Error handling
`CloudberryCommon` seeds verified CB/GP errors (distribution incompatibility,
unique-index-must-contain-distkey, EXCLUDE/FK on distributed tables, AO/PAX/column
-store restrictions) and grows over a few smoke runs, mirroring how `CitusCommon`
was built.

## Testing / acceptance
Build with JDK 17. Run `cloudberry --connection-url postgresql://localhost:7000/test
--oracle NoREC` then `--oracle QUERY_PARTITIONING`. Acceptance: reaches the oracle
phase; 0 crashes / 0 AssertionErrors; `gp_distribution_policy` shows p and r
policies; `relam` shows ao/pax tables — i.e. it genuinely exercises MPP + columnar.
A smoke script is provided; no unit tests (needs a live cluster, as with Citus).

## Out of scope (v2+)
GP partitioning (RANGE/LIST), AO/PAX WITH-options (compression/encoding/blocksize),
resource groups, gp-specific SET GUCs, PQS oracle.
