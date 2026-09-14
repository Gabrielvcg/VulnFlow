# Reading console results

The overview shows severity counts directly, including UNKNOWN.
An execution end timestamp also applies to FAILED requests. It does not imply success.
Requests that fail before ingestion have no processing scan or event identifier.

Findings offers the first 100 completed requests returned by the authorized request list.
Older requests remain accessible from Scans. AWS results use the server cursor; local results use page numbers.
Zero findings means the report contains no findings, not a guarantee that its target is secure.

Publication failures count all retained failed outbox events, not only the last 30 days.
Review the error and report availability before retrying. Disabled targets retain their scan history.
