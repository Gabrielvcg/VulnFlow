# JSON report media type parameters

Report ingestion parses file content types with the existing Spring MediaType parser.
It accepts application/json and valid media types whose subtype ends in +json,
including legal parameters and quoted values. The suffix rule intentionally preserves
the previous compatibility scope, including non-application top-level types and
application/*+json. text/json remains unsupported. Malformed types now consistently
use UnsupportedReportMediaTypeException and the existing HTTP 415 error code.

Empty and oversized reports are rejected before reading bytes. Media type rejection
also precedes byte reading and the submission port, so registration and its storage
work cannot begin. Successful submission still hashes and forwards the exact bytes;
content parsing remains in the existing asynchronous processor. This change does not
claim that a media type proves JSON syntax or alter parser/size/security policy.

Regression tests use the real ingestion service and standalone MockMvc with the real
controller and exception advice, mocked submission and asset ports, and no listening
server. They cover legal parameters, quoted semicolons, casing, legacy suffix scope,
unsupported and malformed types, exact SHA-256/payload forwarding, and size/empty guards.
