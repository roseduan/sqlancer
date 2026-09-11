package sqlancer.cloudberry.oracle.tlp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.ComparatorHelper;
import sqlancer.Randomly;
import sqlancer.cloudberry.gen.CloudberryCommon;
import sqlancer.postgres.PostgresGlobalState;
import sqlancer.postgres.PostgresVisitor;
import sqlancer.postgres.ast.PostgresColumnValue;
import sqlancer.postgres.ast.PostgresExpression;
import sqlancer.postgres.gen.PostgresCommon;
import sqlancer.postgres.oracle.tlp.PostgresTLPBase;

/**
 * TLP oracle for {@code GROUP BY}: {@code SELECT <cols> FROM ... GROUP BY <cols>} (no WHERE) must produce the same
 * distinct group keys as deduplicating the union of the same query partitioned on a predicate P into {@code WHERE P},
 * {@code WHERE NOT P}, and {@code WHERE P IS NULL}, each grouped by the same columns. A given group key can appear in
 * more than one partition (some of its rows satisfy P, others don't), so a plain {@code UNION} (not {@code UNION ALL})
 * is used to fold those back into one occurrence per key, matching the unpartitioned GROUP BY. Reuses the PostgreSQL
 * TLP infrastructure; only the expected-error set is augmented with Cloudberry-specific errors, and generated queries
 * are restricted to IMMUTABLE functions (see {@link CloudberryTLPHavingOracle}) so that non-deterministic functions do
 * not cause false-positive result-set mismatches.
 */
public class CloudberryTLPGroupByOracle extends PostgresTLPBase {

    public CloudberryTLPGroupByOracle(PostgresGlobalState state) {
        super(state);
        PostgresCommon.addGroupingErrors(errors);
        CloudberryCommon.addCloudberryErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        state.setAllowedFunctionTypes(Arrays.asList(PostgresGlobalState.IMMUTABLE));
        try {
            super.check();
            groupByCheck();
        } finally {
            state.setDefaultAllowedFunctionTypes();
        }
    }

    private void groupByCheck() throws SQLException {
        // generateFetchColumns() is package-private in PostgresTLPBase (a different package), so it cannot be
        // overridden here; instead, replace whatever super.check() picked with plain column references -- an
        // arbitrary expression (or the '*' wildcard super.check() can pick) is not always a valid GROUP BY key.
        // PostgresTLPAggregateOracle.aggregateCheck() uses this same "overwrite after super.check()" idiom.
        List<PostgresExpression> groupByColumns = Randomly.nonEmptySubset(targetTables.getColumns()).stream()
                .map(c -> (PostgresExpression) new PostgresColumnValue(c, null)).collect(Collectors.toList());
        select.setFetchColumns(groupByColumns);
        select.setGroupByExpressions(groupByColumns);
        select.setWhereClause(null);
        String originalQueryString = PostgresVisitor.asString(select);
        List<String> resultSet = ComparatorHelper.getResultSetFirstColumnAsString(originalQueryString, errors, state);

        select.setWhereClause(predicate);
        String firstQueryString = PostgresVisitor.asString(select);
        select.setWhereClause(negatedPredicate);
        String secondQueryString = PostgresVisitor.asString(select);
        select.setWhereClause(isNullPredicate);
        String thirdQueryString = PostgresVisitor.asString(select);

        List<String> combinedString = new ArrayList<>();
        List<String> secondResultSet = ComparatorHelper.getCombinedResultSetNoDuplicates(firstQueryString,
                secondQueryString, thirdQueryString, combinedString, true, state, errors);
        ComparatorHelper.assumeResultSetsAreEqual(resultSet, secondResultSet, originalQueryString, combinedString,
                state, ComparatorHelper::canonicalizeResultValue);
    }

}
