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

## Declarative partitioning (added 2026-08-27)
`PostgresTableGenerator.generatePartitionBy()` (inherited, unmodified) already emits
`PARTITION BY {RANGE,LIST,HASH}(...)` for some tables for free -- it was this exact
shape (a partitioned table with **zero leaf partitions**) that produced the
`mj_part` case reproducing the "unexpected gang size" ORCA crash. Two gaps found
while wiring this up properly:
- Every such table was permanently unqueryable with real rows: with no leaf
  partitions, `INSERT` always fails (`"no partition of relation ... found for
  row"`, already an expected error via the inherited `PostgresInsertGenerator`).
  `CloudberryProvider.generateDatabase()` now calls `createLeafPartitions()` for
  any table `pg_partitioned_table` reports a strategy for: HASH gets 2-4
  MODULUS/REMAINDER partitions (type-agnostic, always valid); RANGE/LIST get a
  single catch-all `DEFAULT` partition (also type-agnostic -- no boundary-literal
  generator needed). A low-probability branch still leaves some tables leafless,
  to keep exercising the original crashing shape.
- `redistributeTable()` was being called on partitioned tables and their leaf
  partitions unconditionally. Verified on the live cluster: `SET DISTRIBUTED
  BY/RANDOMLY` on a partitioned root works, but `REPLICATED` fails
  (`"can't set the distribution policy of a partition table to REPLICATED"`),
  and *any* `SET DISTRIBUTED` on a leaf partition fails (`"can't set the
  distribution policy of \"<partition>\" ... can only be the same as its
  parent's"`) -- a partition's policy always follows its root. `generateDatabase()`
  now detects partitioned tables via `pg_partitioned_table.partstrat` and skips
  `redistributeTable()` for them entirely; both error strings were also added to
  `CloudberryCommon` as a defense-in-depth safety net.

Still out of scope: typed RANGE/LIST boundary-literal generation (multiple,
non-overlapping, non-DEFAULT leaf partitions per type), sub-partitioning, legacy
GPDB `PARTITION BY ... (START ... END ...)` syntax (Cloudberry's declarative
partitioning is the standard PG subsystem, not the legacy GPDB one).

## Out of scope (v2+)
AO/PAX WITH-options (compression/encoding/blocksize), resource groups,
gp-specific SET GUCs, PQS oracle.
