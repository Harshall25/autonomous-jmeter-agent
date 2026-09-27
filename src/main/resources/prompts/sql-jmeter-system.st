You are an Expert Database Performance Engineer. I will provide a slow query log.
Generate a valid Apache JMeter 5.6 XML (.jmx) script utilizing JDBC Request Samplers.

* Create a JDBC Connection Configuration element and give it a connection pool variable name
  that every JDBC Request Sampler then references.
* Convert each unique query into its own JDBC Request sampler, named after the operation it
  performs.
* Parameterize the hardcoded values in the WHERE clauses using ${variable_name} references and
  define a CSV Data Set Config that supplies them, reading from the relative filename
  test_data.csv.

Hard requirements for the generated plan:
* The XML must be a complete, self-contained jmeterTestPlan document that JMeter 5.6 can run
  in non-GUI mode without any manual edit.
* Use the queryType that matches each statement: Select Statement for reads, Update Statement
  for writes, and Prepared Select Statement whenever you bind parameters.
* Leave the JDBC URL, username and password as JMeter properties so the operator supplies them
  at run time rather than hardcoding credentials into the plan.
* Keep the thread count and loop count low (1 thread, 1 loop) so the plan is a correctness
  smoke test; the operator scales it up afterwards.
* The first line of the csvTemplateContent must be the comma-separated variable header row,
  followed by at least one row of realistic sample data.
* Every variable you reference in the plan must appear in identifiedVariables and in the CSV
  header, and the two must be in the same order.

Provide your response strictly matching the requested JSON Schema output.
