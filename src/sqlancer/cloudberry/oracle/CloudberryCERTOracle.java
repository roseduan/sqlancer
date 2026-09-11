package sqlancer.cloudberry.oracle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sqlancer.IgnoreMeException;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.postgres.PostgresGlobalState;

/**
 * Wraps the generic {@code CERTOracle} to filter out known-noisy, non-actionable failure modes -- cases where ORCA's
 * cardinality *estimate* is non-monotonic even though nothing is actually wrong, since {@code CERTOracle} only compares
 * row-count estimates parsed from two {@code EXPLAIN} outputs, never real query results:
 *
 * <ul>
 * <li>The {@code mutateGroupBy}/{@code mutateHaving}/{@code mutateDistinct} mutations: small/near-zero group counts for
 * these duplicate-elimination operators routinely trip the monotonicity check.
 * <li>A join whose {@code ON} condition is a compile-time constant not referencing any table column (e.g.
 * {@code ON inet_same_family('a','b')}), which degenerates into a full cross join or an empty result independent of any
 * later mutation and has been observed to produce wildly inconsistent estimates (e.g. 2 vs 34765) purely from that
 * degenerate join shape.
 * <li>A mutation that makes GPORCA fall back to the Postgres planner for only one of the two {@code EXPLAIN} calls
 * (e.g. adding a predicate on a {@code name}-typed column, whose non-default collation GPORCA's translator does not
 * support). The two row-count estimates then come from unrelated cost models entirely, so a "monotonicity violation"
 * between them is meaningless -- {@code CERTOracle}'s own plan-shape edit-distance guard does not catch this, since the
 * plan's node *shapes* can still be identical even though a different optimizer produced each.
 * </ul>
 *
 * Every other mutation kind (join with a real column-based condition, where, window function, ...) is left alone and
 * still reported as a genuine finding.
 */
public class CloudberryCERTOracle implements TestOracle<PostgresGlobalState> {

    // SQLancer's generated identifiers always follow the t<N>/c<N> convention (see e.g. DBMSCommon.createTableName),
    // so a JOIN ... ON clause with no "t<N>.c<N>"-shaped reference anywhere in it is not conditioned on any actual
    // column -- i.e. it is a compile-time constant. Non-greedily captures up to the next top-level clause keyword,
    // the next JOIN/ON pair, a semicolon, or the end of the string, so it doesn't spill past the intended ON clause.
    private static final Pattern JOIN_ON_CLAUSE = Pattern.compile(
            "\\bJOIN\\s+\\S+\\s+ON\\s+(.+?)(?=\\s+WHERE\\b|\\s+GROUP BY\\b|\\s+ORDER BY\\b|\\bJOIN\\b|;|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_REFERENCE = Pattern.compile("\\bt\\d+\\.c\\d+\\b");

    // Recovers the two EXPLAIN query strings embedded in CERTOracle's AssertionError message, whose exact format is
    // "Inconsistent result for query: " + query1 + "; --" + rowCount1 + "\n" + query2 + "; --" + rowCount2 (see
    // CERTOracle.check()). Greedy .* finds the LAST "; --<digits>" on each line, which is correct even on the rare
    // query that happens to contain its own literal "--".
    private static final Pattern TWO_QUERIES = Pattern
            .compile("^Inconsistent result for query: (.*); --\\d+\\n(.*); --\\d+$");

    private final TestOracle<PostgresGlobalState> delegate;
    private final PostgresGlobalState globalState;

    public CloudberryCERTOracle(PostgresGlobalState globalState, TestOracle<PostgresGlobalState> delegate) {
        this.globalState = globalState;
        this.delegate = delegate;
    }

    @Override
    public void check() throws Exception {
        try {
            delegate.check();
        } catch (AssertionError e) {
            String message = e.getMessage();
            if (message != null && message.startsWith("Inconsistent result for query")
                    && (message.contains("GROUP BY") || message.contains("HAVING")
                            || message.contains("SELECT DISTINCT") || hasConstantJoinCondition(message)
                            || usedDifferentOptimizers(message))) {
                throw new IgnoreMeException();
            }
            throw e;
        }
    }

    private static boolean hasConstantJoinCondition(String message) {
        Matcher joinMatcher = JOIN_ON_CLAUSE.matcher(message);
        while (joinMatcher.find()) {
            if (!COLUMN_REFERENCE.matcher(joinMatcher.group(1)).find()) {
                return true;
            }
        }
        return false;
    }

    // Re-runs both EXPLAIN statements and compares their trailing "Optimizer:" line. Re-execution is safe: EXPLAIN
    // never executes the underlying query, and both statements already succeeded once (they are exactly what
    // CERTOracle just ran to produce this finding). Any failure to parse or re-run is treated conservatively --
    // return false (do not suppress) rather than risk hiding a genuine finding.
    private boolean usedDifferentOptimizers(String message) {
        Matcher matcher = TWO_QUERIES.matcher(message);
        if (!matcher.matches()) {
            return false;
        }
        try {
            String optimizer1 = getOptimizerLine(matcher.group(1));
            String optimizer2 = getOptimizerLine(matcher.group(2));
            return optimizer1 != null && optimizer2 != null && !optimizer1.equals(optimizer2);
        } catch (Exception e) {
            return false;
        }
    }

    private String getOptimizerLine(String explainQuery) throws Exception {
        SQLQueryAdapter q = new SQLQueryAdapter(explainQuery);
        String optimizerLine = null;
        try (SQLancerResultSet rs = q.executeAndGet(globalState)) {
            if (rs != null) {
                while (rs.next()) {
                    String content = rs.getString(1).trim();
                    if (content.startsWith("Optimizer:")) {
                        optimizerLine = content;
                    }
                }
            }
        }
        return optimizerLine;
    }

}
