package sqlancer.cloudberry.oracle;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.Randomly;
import sqlancer.cloudberry.gen.CloudberryCommon;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.postgres.PostgresGlobalState;
import sqlancer.postgres.PostgresSchema.PostgresDataType;
import sqlancer.postgres.PostgresVisitor;
import sqlancer.postgres.oracle.tlp.PostgresTLPBase;

/**
 * A differential test oracle specific to Apache Cloudberry, which ships two query optimizers: GPORCA
 * ({@code optimizer=on}) and the PostgreSQL-derived planner ({@code optimizer=off}). The same query must return the
 * same result regardless of which optimizer produced the plan; a mismatch reveals a logic bug in one of the two
 * optimizers. This reuses the PostgreSQL TLP query builder to generate a random SELECT, then executes it once under
 * each optimizer and compares the result sets.
 *
 * <p>
 * Generated queries are restricted to IMMUTABLE functions so that non-deterministic values do not cause false-positive
 * mismatches. Result sets are compared order-insensitively (as a multiset), so no ORDER BY is required.
 */
public class CloudberryOptimizerOracle extends PostgresTLPBase {

    public CloudberryOptimizerOracle(PostgresGlobalState state) {
        super(state);
        CloudberryCommon.addCloudberryErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        state.setAllowedFunctionTypes(Arrays.asList(PostgresGlobalState.IMMUTABLE));
        try {
            super.check();
            if (Randomly.getBoolean()) {
                select.setWhereClause(gen.generateExpression(PostgresDataType.BOOLEAN));
            }
            String queryString = PostgresVisitor.asString(select);

            // The oracle always sets the optimizer explicitly before each execution, so no RESET is needed afterwards.
            setOptimizer(false);
            List<String> plannerResult = ComparatorHelper.getResultSetFirstColumnAsString(queryString, errors, state);
            setOptimizer(true);
            List<String> orcaResult = ComparatorHelper.getResultSetFirstColumnAsString(queryString, errors, state);

            ComparatorHelper.assumeResultSetsAreEqual(plannerResult, orcaResult, queryString,
                    Arrays.asList(queryString), state);
        } finally {
            state.setDefaultAllowedFunctionTypes();
        }
    }

    private void setOptimizer(boolean useOrca) throws SQLException {
        String sql = "SET optimizer = " + (useOrca ? "on" : "off");
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(sql);
        }
        new SQLQueryAdapter(sql).execute(state);
    }

}
