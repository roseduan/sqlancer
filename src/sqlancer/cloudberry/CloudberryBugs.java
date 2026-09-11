package sqlancer.cloudberry;

// do not make the fields final to avoid warnings
public final class CloudberryBugs {

    // A GPORCA coordinator crash (SIGSEGV), not just a controlled error: CExtendedStatsProcessor::
    // ApplyCorrelatedStatsToScaleFactorFilterCalculation (CExtendedStatsProcessor.cpp) dereferences
    // the result of colid_to_attno_mapping->Find(&colid) without checking for null. Reachable when a
    // table has a "CREATE STATISTICS ... (dependencies) ON <subset of columns>" extended-stats object
    // and a conjunctive filter (through a subquery/alias boundary, e.g. GROUP BY + an outer WHERE on
    // the derived columns) includes a clause on a column outside that subset -- Find() legitimately
    // returns null for that clause, and the code dereferences it anyway. Unlike a normal expected
    // error, this kills the coordinator backend and can cascade into unrelated concurrent sessions
    // ("database system is in recovery mode"), so it cannot be handled by whitelisting an error
    // message -- CREATE_STATISTICS generation is disabled outright in CloudberryProvider while true.
    public static boolean bugExtendedStatsDependenciesSegfault = true;

    // A known GPORCA bug (CPhysicalFullMergeJoin::PdsDerive mis-deriving distribution for a FULL MERGE
    // JOIN with a Universal-distributed outer child) already fixed locally but not yet merged into the
    // Cloudberry main branch this is built from, so it can still surface against this cluster.
    public static boolean bugFullMergeJoinUnexpectedGangSize = true;

    // A known GPORCA bug: a boolean-typed INCLUDE-only index column matched by a bare/`= true` predicate
    // gets treated as a valid index key column when building an Index Cond (likely via CXformUtils::
    // PdrgpcrIndexColumns, which concatenates key and included columns into one array with no marker
    // distinguishing the two). The resulting plan references an index attribute number beyond
    // indnkeyatts, which the executor's own sanity check in nodeIndexscan.c rejects. Non-MPP-specific
    // (reproduces under DISTRIBUTED REPLICATED too) and optimizer=on-specific (the Postgres planner
    // path is unaffected). Already fixed locally but not yet merged into the Cloudberry main branch
    // this is built from.
    public static boolean bugBogusIndexQualification = true;

    // A real, still-open planner bug, not Cloudberry-specific: clauselist_selectivity()'s OR-clause
    // combinator in clausesel.c ("s1 = s1 + s2 - s1 * s2") never re-clamps its result to [0, 1] before
    // returning it. With a sparse ANALYZE sample, a range-ish predicate's selectivity estimate can drift
    // a hair above 1.0, and that out-of-range value later blows the unrelated postcondition
    // Assert(pselec >= 0.0 && pselec <= 1.0) in adjust_selectivity_for_nulltest() (costsize.c:5436),
    // which runs for any outer join regardless of whether the qual is actually a null test.
    // Assert-build-only (production builds would just silently keep the slightly-wrong selectivity);
    // minimal repro and analysis captured, fix not yet applied upstream. Unlike the two bugs above
    // (both plain elog(ERROR), which only abort the current transaction), this is a hard abort() that
    // kills the whole backend process and forces a coordinator-wide restart -- every other
    // concurrently-running thread's connection briefly fails too (self-recovers within a few seconds,
    // no manual action needed).
    public static boolean bugOrSelectivityNotClampedAssert = true;

    private CloudberryBugs() {
    }

}
