package sqlancer.cloudberry.oracle.tlp;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import sqlancer.ComparatorHelper;
import sqlancer.cloudberry.gen.CloudberryCommon;
import sqlancer.postgres.PostgresGlobalState;
import sqlancer.postgres.PostgresVisitor;
import sqlancer.postgres.ast.PostgresSelect.SelectType;
import sqlancer.postgres.gen.PostgresCommon;
import sqlancer.postgres.oracle.tlp.PostgresTLPBase;

/**
 * TLP oracle for {@code SELECT DISTINCT}: {@code SELECT DISTINCT <cols> FROM ...} (no WHERE) must return the same rows
 * as deduplicating the union of the same query partitioned on a predicate P into {@code WHERE P}, {@code WHERE NOT P},
 * and {@code WHERE P IS NULL} -- every row satisfies exactly one of the three, so the union (with duplicates removed)
 * covers exactly the same rows as the unpartitioned query. Reuses the PostgreSQL TLP infrastructure; only the
 * expected-error set is augmented with Cloudberry-specific errors, and generated queries are restricted to IMMUTABLE
 * functions (see {@link CloudberryTLPHavingOracle}) so that non-deterministic functions do not cause false-positive
 * result-set mismatches.
 */
public class CloudberryTLPDistinctOracle extends PostgresTLPBase {

    public CloudberryTLPDistinctOracle(PostgresGlobalState state) {
        super(state);
        PostgresCommon.addGroupingErrors(errors);
        CloudberryCommon.addCloudberryErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        state.setAllowedFunctionTypes(Arrays.asList(PostgresGlobalState.IMMUTABLE));
        try {
            super.check();
            distinctCheck();
        } finally {
            state.setDefaultAllowedFunctionTypes();
        }
    }

    private void distinctCheck() throws SQLException {
        select.setSelectOption(SelectType.DISTINCT);
        select.setWhereClause(null);
        String originalQueryString = PostgresVisitor.asString(select);
        List<String> resultSet = ComparatorHelper.getResultSetFirstColumnAsString(originalQueryString, errors, state);

        select.setWhereClause(predicate);
        String firstQueryString = PostgresVisitor.asString(select);
        select.setWhereClause(negatedPredicate);
        String secondQueryString = PostgresVisitor.asString(select);
        select.setWhereClause(isNullPredicate);
        String thirdQueryString = PostgresVisitor.asString(select);

        String unionString = "SELECT DISTINCT * FROM (" + firstQueryString + " UNION ALL " + secondQueryString
                + " UNION ALL " + thirdQueryString + ") as tlpDistinct";
        List<String> combinedString = new ArrayList<>();
        combinedString.add(unionString);
        List<String> secondResultSet = ComparatorHelper.getResultSetFirstColumnAsString(unionString, errors, state);
        ComparatorHelper.assumeResultSetsAreEqual(resultSet, secondResultSet, originalQueryString, combinedString,
                state, ComparatorHelper::canonicalizeResultValue);
    }

}
