# Phase 0 loader decision

The feasibility gate uses a small internal `URLClassLoader` container per
generation. PF4J 3.15.0 was not added to the production dependency graph:

- a standard PF4J manager indexes plugins by id and therefore cannot keep the
  active and candidate generations of one id loaded together;
- one PF4J manager per generation could avoid that identity collision, but FSR
  would still own shadow copies, parent-first API policy, candidate staging,
  atomic routing, cleanup, and leak telemetry;
- the executable fixture proves those Phase 0 requirements with the JDK-only
  loader, so PF4J adds packaging and relocation work without reducing the
  lifecycle work required from FSR.

This is an internal-engine decision, not public SPI. It can be revisited before
production integration if a later phase demonstrates concrete value from PF4J
lifecycle or extension discovery.
