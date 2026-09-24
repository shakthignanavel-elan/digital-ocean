Expectations
1. Quota enforcement: Accept an identifier (tenant id) and the resource namespace. return an allow deny descision with remaining quota and reset time, support at least two algorithm choices (token bucket and sliding wingow)
2. Configuration api: Allow limti rules to be created, updated and deleted at runtime without restarting the service. Rules should be for burst and sustained rate distictions
3. Multi instance correctness : the service shoudl provide correct descisions even when two or more instances share state, not just when running a single process
4. Observable quota state - provide an endpoint returning current quota consumption, remaining capacity and reset timestamps for given identifier