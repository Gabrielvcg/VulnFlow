# Reading console results

The overview shows severity counts directly, including UNKNOWN.
An execution end timestamp also applies to FAILED requests. It does not imply success.
Requests that fail before ingestion have no processing scan or event identifier.

Findings offers the first 100 completed requests returned by the authorized request list.
Older requests remain accessible from Scans. AWS results use the server cursor; local results use page numbers.
Zero findings means the report contains no findings, not a guarantee that its target is secure.

Publication failures count all retained failed outbox events, not only the last 30 days.
Review the error and report availability before retrying. Disabled targets retain their scan history.

AWS console findings search filters and stably sorts existing processing-result references before
creating response views for the selected page. Ordering remains descending risk, vulnerability ID,
then finding key, with the existing natural comparator null behavior. Case folding uses Locale.ROOT.
Only returned rows are converted to finding views; exact totals still require reading all batches
and sorting all matching references. This reduces view construction, not database reads or the
full-sort cost. The 100,000-row search cap, reader errors and completion guard remain unchanged.

The dashboard keeps its 30-day window and 500-recent-scan selection. For the selected latest scans,
AWS summary counts are added directly; only scans without a summary use local findings counts.
Local fallback uses one existing bulk count per severity (five calls with the current enum) instead
of one count per scan and severity. Empty/all-AWS selections issue no local count or empty IN query.
Totals remain long values, including UNKNOWN. Latest-scan timestamp ties still include every matching
distinct scan ID; this change does not introduce a tie-break or redefine current posture.
Mocked repository call budgets do not measure SQL latency or validate database execution plans.
