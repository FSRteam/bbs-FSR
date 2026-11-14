# BBS Curve Fixer attribution

BBS FS integrates source code from [quaIett/bbs-curvefixer-addon](https://github.com/quaIett/bbs-curvefixer-addon),
upstream `main` at `c033a585a459e2e919d62342c84cc92943977a09` (imported 2026-09-19).
Copyright (c) 2026 qualet. Licensed under the MIT License; the complete notice is
included in every BBS jar at `META-INF/licenses/bbs-curvefixer-addon-LICENSE.txt`.

The imported history contains `src/`, `LICENSE`, and the three README files.
Bundled dependency jars and standalone build infrastructure were excluded.
All nine commits retain the upstream author, committer, timestamps, commit messages,
and co-author trailers. Filtering changes commit hashes; the mapping is below.
The integration merge connects this source history to BBS FS without replacing
BBS build files or packaging a second mod.

Native integration covers float option eligibility and whole-identifier filtering,
the Iris menu snapshot and shader option picker, and the world playback curve fallback.
The picker uses BBS FS surfaces and translations. Addon entrypoints, settings/event
registration, RefreshedUI dependencies, and mixins into BBS itself are unnecessary.
The original all-curves list remains accessible from the picker and without Iris.

Integration fixes also cover prefixed channel duplicate checks, switching from the
picker to the list after adding channels, directives at file boundaries, and stale
world curve values in gaps between camera clips. The world fallback includes BBS FS's
horizontal sun rotation channel alongside the original built-in channels.

| Upstream commit | Imported commit | Author |
| --- | --- | --- |
| `70f6a2e92c1cdaf9d6a8628c756d3676d77ba2a3` | `0a9161dd84a1a3742bfffafacd160ee8ad71050b` | qualet <qualetprod@gmail.com> |
| `f69b3a55642362ea65a2847314ad501249928676` | `bbe41dc93a5a241126fe816ac610a4014a2b76a7` | qualet <qualetprod@gmail.com> |
| `27f8f1d2c3ff9a8df95d427950d7d8804394f1ca` | `47573b7951dad8c9d1956e141003e158793796ac` | qualet <qualetprod@gmail.com> |
| `e3fa832d908b36d27704b2b76e247cb888806862` | `19b50d93080cd8b4e53973ea96b08721d3d5346e` | qualet <qualetprod@gmail.com> |
| `ed2f46130ec3564ddc313af49100a42ee385fbe7` | `64f032feea5d196664110127e1ccc0ceb7eddc19` | qualet <qualetprod@gmail.com> |
| `068e42a6bc5d0a6418aea1c3e8d4d16b1efa8f7e` | `b09b736edfeb45c6031b65db136f720c0fe04077` | qualet <qualetprod@gmail.com> |
| `58ec2c09f1c7055b670e13c3e3fb4f2106eb2e6e` | `97ba82403b91577113190eec823821cf59aec25b` | qualet <qualetprod@gmail.com> |
| `62e1c653e5df66fa28017c993fc7e8ae505d5171` | `742756242f5ff350f781bae08f91177711096f8a` | skebob <skebob@example.com> |
| `c033a585a459e2e919d62342c84cc92943977a09` | `c6dfdb850305c35a97ff38409e58040719ba86e3` | qualet <qualetprod@gmail.com> |
