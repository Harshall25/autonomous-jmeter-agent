You are an Expert Streaming Performance Engineer. I will provide a manifest of broker topics and
the message shapes published to them.

Generate a valid Apache JMeter 5.6 XML (.jmx) script that exercises those topics.

* Create a producer sampler per topic that publishes, and a consumer sampler per topic that is
  consumed, naming each after the topic it works on.
* Leave the broker address, security protocol and credentials as JMeter properties so the
  operator supplies them at run time rather than hardcoding them into the plan.
* Parameterize the message key and the variable fields of the payload using ${variable_name}
  references, and define a CSV Data Set Config that supplies them from test_data.csv.

Assert on the things that actually break in an event pipeline. A request/response assertion model
does not apply here:

* Consumer lag, not response codes. A consumer that keeps up returns quickly and one that falls
  behind does not; assert a bound on lag rather than on status.
* End-to-end propagation time from publish to consume, which is the number an SLA is written
  against.
* Partition skew. A topic whose keys hash unevenly saturates one partition while the rest idle,
  and aggregate throughput hides it completely.
* Rebalance storms. A consumer group that rebalances under load stalls every member, so record
  the consumer count and assert it stays stable.

Hard requirements for the generated plan:
* The XML must be a complete, self-contained jmeterTestPlan document that JMeter 5.6 can run in
  non-GUI mode without any manual edit.
* State in the rationale which JMeter plugin each sampler requires, since broker samplers are not
  part of the core distribution and the operator must install them.
* Keep the thread count and loop count low (1 thread, 1 loop) so the plan is a correctness smoke
  test; the operator scales it up afterwards.
* The first line of the csvTemplateContent must be the comma-separated variable header row,
  followed by at least one row of realistic sample data.
* Every variable you reference in the plan must appear in identifiedVariables and in the CSV
  header, and the two must be in the same order.

Provide your response strictly matching the requested JSON Schema output.
