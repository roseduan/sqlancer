package sqlancer.cloudberry;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.auto.service.AutoService;

import sqlancer.DatabaseProvider;
import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.StatementExecutor;
import sqlancer.cloudberry.gen.CloudberryCommon;
import sqlancer.cloudberry.gen.CloudberryTableGenerator;
import sqlancer.common.DBMSCommon;
import sqlancer.common.oracle.CompositeTestOracle;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.postgres.PostgresGlobalState;
import sqlancer.postgres.PostgresOptions;
import sqlancer.postgres.PostgresProvider;
import sqlancer.postgres.PostgresSchema.PostgresTable;
import sqlancer.postgres.PostgresSchema.PostgresTable.TableType;

/**
 * SQLancer provider for Apache Cloudberry, a PostgreSQL-based MPP database. It extends the PostgreSQL provider and
 * reuses PostgreSQL's expression generators and NoREC/TLP oracles.
 *
 * <p>
 * Cloudberry is a transparent MPP system: a single connection to the coordinator is sufficient, so there is no
 * worker-node registration and no extension to create; {@code createDatabase} is inherited unchanged. The only
 * Cloudberry-specific setup is assigning a distribution policy to each table via {@code ALTER TABLE ... SET
 * DISTRIBUTED}, choosing the distribution key so that it is a subset of any PRIMARY KEY / UNIQUE constraint. Storage
 * formats (heap / ao_row / ao_column / pax) are exercised for free through the inherited {@code USING <am>} clause.
 */
@AutoService(DatabaseProvider.class)
public class CloudberryProvider extends PostgresProvider {

    @SuppressWarnings("unchecked")
    public CloudberryProvider() {
        super((Class<PostgresGlobalState>) (Object) CloudberryGlobalState.class,
                (Class<PostgresOptions>) (Object) CloudberryOptions.class);
    }

    @Override
    public String getDBMSName() {
        return "cloudberry";
    }

    @Override
    public SQLConnection createDatabase(PostgresGlobalState globalState) throws SQLException {
        // PostgresProvider.createDatabase() has the same guard, but it checks
        // "o == PostgresOracleFactory.PQS", a different enum type than CloudberryOracleFactory.PQS, so it can never
        // match here and Cloudberry's PQS silently ran without it. Without generateOnlyKnown, the table generator
        // includes DECIMAL/FLOAT/REAL/INET/RANGE/MONEY/BIT columns, whose PostgresConstant implementations extend
        // PostgresConstantBase -- a deliberately-unfinished base class where isEquals()/isLessThan()/cast() are all
        // still just "return null;" placeholders. PQS calls those to compute expected values while rectifying a
        // predicate, and the resulting null then gets dereferenced a call or two up the stack (e.g.
        // "could not find random row!", or an NPE like "... because ... is null"), rather than surfacing as
        // an IgnoreMeException the way an unsupported case normally does elsewhere in this codebase.
        if (((CloudberryOptions) globalState.getDbmsSpecificOptions()).cloudberryOracle
                .contains(CloudberryOracleFactory.PQS)) {
            generateOnlyKnown = true;
        }
        return super.createDatabase(globalState);
    }

    @Override
    protected void createTables(PostgresGlobalState globalState, int numTables) throws Exception {
        while (globalState.getSchema().getDatabaseTables().size() < numTables) {
            try {
                String tableName = DBMSCommon.createTableName(globalState.getSchema().getDatabaseTables().size());
                SQLQueryAdapter createTable = CloudberryTableGenerator.generate(tableName, globalState.getSchema(),
                        generateOnlyKnown, globalState);
                globalState.executeStatement(createTable);
            } catch (IgnoreMeException e) {

            }
        }
    }

    @Override
    public void generateDatabase(PostgresGlobalState globalState) throws Exception {
        readFunctions(globalState);
        // The inherited PostgresTableGenerator already emits "PARTITION BY {RANGE,LIST,HASH}(...)" for
        // some tables (declarative partitioning), with no leaf partitions of its own. Give those tables
        // actual leaf partitions here rather than leaving every one permanently empty.
        createTables(globalState, Randomly.fromOptions(4, 5, 6));
        for (PostgresTable table : globalState.getSchema().getDatabaseTables()) {
            if (table.isView() || table.getTableType() == TableType.TEMPORARY) {
                continue;
            }
            Character partitionStrategy = getPartitionStrategy(table.getName(), globalState);
            if (partitionStrategy != null) {
                // Distribution policy of a partitioned table (and of its leaf partitions) cannot be set
                // independently -- it always follows the root -- so redistributeTable() does not apply here.
                createLeafPartitions(table.getName(), partitionStrategy, globalState);
                continue;
            }
            // Keep the default (auto-assigned) distribution for some tables to exercise that path too.
            if (Randomly.getBooleanWithRatherLowProbability()) {
                continue;
            }
            redistributeTable(table.getName(), globalState);
        }
        globalState.updateSchema();
        prepareTables(globalState);
    }

    /**
     * Returns the partition strategy of a declaratively partitioned table, or {@code null} if the table is not
     * partitioned.
     *
     * @param tableName
     *            the table to inspect
     * @param globalState
     *            the global state, used to obtain a connection
     *
     * @return {@code 'r'}=RANGE, {@code 'l'}=LIST, {@code 'h'}=HASH, or {@code null} if not partitioned
     */
    private static Character getPartitionStrategy(String tableName, PostgresGlobalState globalState)
            throws SQLException {
        String queryString = "SELECT pt.partstrat FROM pg_class c "
                + "JOIN pg_partitioned_table pt ON pt.partrelid = c.oid WHERE c.relname = '" + tableName + "';";
        SQLQueryAdapter query = new SQLQueryAdapter(queryString);
        try (SQLancerResultSet rs = query.executeAndGet(globalState)) {
            if (rs == null || !rs.next()) {
                return null;
            }
            String strat = rs.getString(1);
            return strat == null || strat.isEmpty() ? null : strat.charAt(0);
        }
    }

    /**
     * Adds leaf partitions to a declaratively partitioned table. HASH gets a small number of MODULUS/REMAINDER
     * partitions that together cover every row; RANGE and LIST get a single catch-all DEFAULT partition, which needs no
     * type-specific boundary literals. Some tables are deliberately left with zero leaf partitions: that exact shape (a
     * partitioned table ORCA derives an empty/Universal distribution for) is what originally triggered the "unexpected
     * gang size" crash in CPhysicalFullMergeJoin::PdsDerive, so it needs to stay in the generated state space.
     *
     * @param tableName
     *            the partitioned table to add leaf partitions to
     * @param strategy
     *            the table's partition strategy, as returned by {@link #getPartitionStrategy}
     * @param globalState
     *            the global state, used to execute the generated statements
     */
    private static void createLeafPartitions(String tableName, char strategy, PostgresGlobalState globalState)
            throws Exception {
        if (Randomly.getBooleanWithRatherLowProbability()) {
            return;
        }
        if (strategy == 'h') {
            int modulus = Randomly.fromOptions(2, 3, 4);
            for (int remainder = 0; remainder < modulus; remainder++) {
                String childName = tableName + "_h" + remainder;
                String queryString = "CREATE TABLE " + childName + " PARTITION OF " + tableName
                        + " FOR VALUES WITH (MODULUS " + modulus + ", REMAINDER " + remainder + ")";
                globalState.executeStatement(new SQLQueryAdapter(queryString, getCloudberryErrors()));
            }
        } else {
            String childName = tableName + "_default";
            String queryString = "CREATE TABLE " + childName + " PARTITION OF " + tableName + " DEFAULT";
            globalState.executeStatement(new SQLQueryAdapter(queryString, getCloudberryErrors()));
        }
    }

    /**
     * Re-assigns the distribution policy of a table. The distribution key must be a subset of every PRIMARY KEY /
     * UNIQUE constraint, so when constraints exist only their (intersected) columns are eligible. Falls back to
     * {@code RANDOMLY} when no eligible column exists, and occasionally chooses {@code REPLICATED} for unconstrained
     * tables.
     *
     * @param tableName
     *            the table to redistribute
     * @param globalState
     *            the global state, used to execute the generated statement
     */
    private static void redistributeTable(String tableName, PostgresGlobalState globalState) throws Exception {
        List<String> constraintTypes = getTableConstraints(tableName, globalState);
        List<String> eligibleColumns = getEligibleDistributionColumns(tableName, constraintTypes, globalState);

        String policyClause;
        if (constraintTypes.isEmpty()) {
            // Unconstrained table: free choice among hash / random / replicated distribution.
            if (Randomly.getBooleanWithRatherLowProbability()) {
                policyClause = "REPLICATED";
            } else if (eligibleColumns.isEmpty() || Randomly.getBooleanWithSmallProbability()) {
                policyClause = "RANDOMLY";
            } else {
                policyClause = "BY (" + Randomly.fromList(eligibleColumns) + ")";
            }
        } else {
            // With a PRIMARY KEY / UNIQUE constraint the distribution key must be a subset of it, and the table
            // cannot be distributed RANDOMLY or REPLICATED. If no single column satisfies all constraints, keep the
            // default (auto-assigned) distribution rather than issuing a doomed ALTER.
            if (eligibleColumns.isEmpty()) {
                return;
            }
            policyClause = "BY (" + Randomly.fromList(eligibleColumns) + ")";
        }

        String queryString = "ALTER TABLE " + tableName + " SET DISTRIBUTED " + policyClause;
        globalState.executeStatement(new SQLQueryAdapter(queryString, getCloudberryErrors()));
    }

    private static List<String> getTableConstraints(String tableName, PostgresGlobalState globalState)
            throws SQLException {
        List<String> constraints = new ArrayList<>();
        String queryString = "SELECT constraint_type FROM information_schema.table_constraints WHERE table_name = '"
                + tableName
                + "' AND (constraint_type = 'PRIMARY KEY' OR constraint_type = 'UNIQUE' OR constraint_type = 'EXCLUDE');";
        SQLQueryAdapter query = new SQLQueryAdapter(queryString);
        try (SQLancerResultSet rs = query.executeAndGet(globalState)) {
            if (rs == null) {
                return constraints;
            }
            while (rs.next()) {
                constraints.add(rs.getString(1));
            }
        }
        return constraints;
    }

    /**
     * Returns the column names eligible to be a single-column distribution key: hash-distributable columns that appear
     * in every PRIMARY KEY / UNIQUE / EXCLUDE constraint of the table (all columns, if the table is unconstrained).
     *
     * @param tableName
     *            the table to inspect
     * @param constraintTypes
     *            the constraint types already found on the table, as returned by {@link #getTableConstraints}
     * @param globalState
     *            the global state, used to obtain a connection
     *
     * @return the eligible column names
     */
    private static List<String> getEligibleDistributionColumns(String tableName, List<String> constraintTypes,
            PostgresGlobalState globalState) throws SQLException {
        List<String> eligible = new ArrayList<>();
        if (constraintTypes.isEmpty()) {
            String queryString = "SELECT column_name, data_type FROM information_schema.columns WHERE table_name = '"
                    + tableName + "';";
            SQLQueryAdapter query = new SQLQueryAdapter(queryString);
            try (SQLancerResultSet rs = query.executeAndGet(globalState)) {
                if (rs == null) {
                    return eligible;
                }
                while (rs.next()) {
                    String columnName = rs.getString(1);
                    String dataType = rs.getString(2);
                    if (isHashDistributable(dataType)) {
                        eligible.add(columnName);
                    }
                }
            }
            return eligible;
        }

        Map<String, Integer> columnConstraintCount = new HashMap<>();
        String queryString = "SELECT c.column_name, c.data_type FROM information_schema.table_constraints tc "
                + "JOIN information_schema.constraint_column_usage AS ccu USING (constraint_schema, constraint_name) "
                + "JOIN information_schema.columns AS c ON c.table_schema = tc.constraint_schema "
                + "AND tc.table_name = c.table_name AND ccu.column_name = c.column_name "
                + "WHERE (constraint_type = 'PRIMARY KEY' OR constraint_type = 'UNIQUE' OR constraint_type = 'EXCLUDE') "
                + "AND c.table_name = '" + tableName + "';";
        SQLQueryAdapter query = new SQLQueryAdapter(queryString);
        Map<String, String> columnType = new HashMap<>();
        try (SQLancerResultSet rs = query.executeAndGet(globalState)) {
            if (rs == null) {
                return eligible;
            }
            while (rs.next()) {
                String columnName = rs.getString(1);
                String dataType = rs.getString(2);
                columnType.put(columnName, dataType);
                columnConstraintCount.merge(columnName, 1, Integer::sum);
            }
        }
        // A column is eligible only if it participates in all of the table's constraints and is hash-distributable.
        for (Map.Entry<String, Integer> e : columnConstraintCount.entrySet()) {
            if (e.getValue() == constraintTypes.size() && isHashDistributable(columnType.get(e.getKey()))) {
                eligible.add(e.getKey());
            }
        }
        return eligible;
    }

    private static boolean isHashDistributable(String dataType) {
        // Types without a default hash operator class cannot be a Cloudberry distribution key.
        return !(dataType.equals("money") || dataType.equals("bit varying") || dataType.equals("xml")
                || dataType.equals("json") || dataType.startsWith("point") || dataType.startsWith("box")
                || dataType.startsWith("line") || dataType.startsWith("circle") || dataType.startsWith("polygon"));
    }

    @Override
    protected TestOracle<PostgresGlobalState> getTestOracle(PostgresGlobalState globalState) throws SQLException {
        List<TestOracle<PostgresGlobalState>> oracles = ((CloudberryOptions) globalState
                .getDbmsSpecificOptions()).cloudberryOracle.stream().map(o -> {
                    try {
                        return o.create(globalState);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }).collect(Collectors.toList());
        return new CompositeTestOracle<PostgresGlobalState>(oracles, globalState);
    }

    private static ExpectedErrors getCloudberryErrors() {
        ExpectedErrors errors = new ExpectedErrors();
        CloudberryCommon.addCloudberryErrors(errors);
        return errors;
    }

    // Overridden solely to stop generating CREATE STATISTICS while
    // CloudberryBugs.bugExtendedStatsDependenciesSegfault is set: that action's "dependencies"-kind
    // extended statistics can crash the coordinator (see CloudberryBugs for the root cause), and
    // unlike a normal expected error a crash disrupts unrelated concurrent sessions too, so it can't
    // be handled by whitelisting an error message. Otherwise identical to
    // PostgresProvider.prepareTables(); every action's own generator and frequency come straight from
    // PostgresProvider.Action / PostgresProvider.mapActions.
    @Override
    protected void prepareTables(PostgresGlobalState globalState) throws Exception {
        StatementExecutor<PostgresGlobalState, PostgresProvider.Action> se = new StatementExecutor<>(globalState,
                PostgresProvider.Action.values(), CloudberryProvider::mapActionsWithBugWorkarounds, (q) -> {
                    if (globalState.getSchema().getDatabaseTables().isEmpty()) {
                        throw new IgnoreMeException();
                    }
                });
        se.executeStatements();
        globalState.executeStatement(new SQLQueryAdapter("COMMIT", true));
        globalState.executeStatement(new SQLQueryAdapter("SET SESSION statement_timeout = 5000;\n"));
    }

    private static int mapActionsWithBugWorkarounds(PostgresGlobalState globalState, PostgresProvider.Action a) {
        if (CloudberryBugs.bugExtendedStatsDependenciesSegfault && a == PostgresProvider.Action.CREATE_STATISTICS) {
            return 0;
        }
        return PostgresProvider.mapActions(globalState, a);
    }

}
