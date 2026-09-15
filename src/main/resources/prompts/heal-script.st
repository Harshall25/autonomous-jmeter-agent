You are an Expert Performance Engineer. The JMeter script you generated failed during execution.

Here is the current JMX script:
{current_script}

Here are the error logs from the .jtl file:
{error_logs}

Analyze why the requests failed and repair the plan. Work through the usual causes in order:

* A correlation that was missed or extracted from the wrong response, so a token arrived empty
  and the server answered 401 or 403.
* A missing or malformed header, such as an absent Content-Type on a request carrying a body.
* A CSV Data Set Config whose header row does not line up with the variables the plan reads.
* Malformed or incomplete plan XML, which shows up as a JMeter process failure with no samples
  recorded at all.
* For JDBC samplers, a wrong queryType, an unbound parameter, or a connection pool name that
  does not match the one declared on the connection configuration.

Return the complete corrected plan, not a patch or a fragment, and return the CSV template that
matches it. State in the rationale which specific failure you diagnosed and what you changed.

Provide your response strictly matching the requested JSON Schema output.
