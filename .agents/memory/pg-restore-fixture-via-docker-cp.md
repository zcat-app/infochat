---
name: pg-restore-fixture-via-docker-cp
description: "docker cp the dump into the container and pg_restore the in-container path — piping the dump over stdin loses the custom-format magic header and the restore misreads or refuses it."
metadata:
  type: project
---

`docker exec -i <pg> pg_restore ... < fixture.dump` feeds the dump over stdin;
the pipe path loses the custom-format magic header and pg_restore does not
recognize the archive. The reliable shape is:

```bash
docker cp fixture.dump <container>:/tmp/fixture.dump
docker exec <container> pg_restore -U <role> -d <db> /tmp/fixture.dump
```

**How to apply:** any disposable/throwaway database fixture load (test
instances, remediation legs) goes through `docker cp` first. A restore that
immediately errors on the header or restores zero objects is this trap, not a
corrupt dump.
