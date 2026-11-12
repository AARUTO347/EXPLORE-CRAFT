# Mindustry Upgrade Plan

Status labels: **DONE** (applied and verified), **PLANNED** (not applied yet), **BLOCKED** (needs something this machine doesn't have).

## Phase 1 verification (Windows 11, Temurin 17.0.21, Gradle 9.3.1, Arc @ 7445105)
| Check | Result |
|---|---|
| Baseline `tests:test` on the original code | PASS: 335 tests, 0 failures |
| `run-windows.bat test` after the upgrade | PASS: 337 tests (335 + 2 new), 0 failures, 0 errors |
| `run-windows.bat dist` (`desktop:dist server:dist`, includes sprite packing) | PASS: `Mindustry.jar` 88.5 MB, `server-release.jar` 19.4 MB |
| Dedicated server on Windows: boot, `host`, `status`, `save`, `stop`, `exit` | PASS: map loaded, port 6567 opened, `1.msav` written, clean exit 0 |
| Desktop client on Windows | PASS: SDL 2.32.8, OpenGL 4.6 (Intel UHD), audio, main menu rendered, loaded in 15.6 s |
| `run-windows.bat` checks (Java 8 rejected, missing Java, bad mode) | PASS |
| Android / iOS builds | NOT TESTED (no Android SDK, no macOS) |

## Phase 1 — Applied in this pass

### Build and tooling
| Change | File | Status |
|---|---|---|
| Removed `--illegal-access=permit` (ignored since JDK 17 and prints a warning); forced `-Dfile.encoding=UTF-8` (Windows defaulted to `windows-1252`) | `gradle.properties` | DONE |
| Wrapper: pinned `distributionSha256Sum`, `networkTimeout=60000`, `validateDistributionUrl=true` | `gradle/wrapper/gradle-wrapper.properties` | DONE |
| JUnit 5.7.1 → 5.14.4 via `junit-bom` (launcher version now aligned) | `build.gradle` | DONE |
| org.json 20230618 → 20260814, jline 4.0.0 → 4.5.0, guava 33.3.1 → 33.8.0, javapoet 1.12.1 → 1.13.0, lz4-java 1.12.0 → 1.14.0 | `build.gradle` | DONE |
| `gradle/actions/wrapper-validation` v3 → v6 (matches `setup-gradle@v6`) | `.github/workflows/gradle-wrapper-validation.yml` | DONE |
| Windows launcher with JDK 17 check: `run-windows.bat [desktop\|server\|dist\|test\|jar\|server-jar]` | `run-windows.bat` | DONE |
| Test harness: startup wait now fails after 120 s instead of hanging forever | `tests/.../ApplicationTests.java` | DONE |

### Security and bug fixes
| Fix | File | Status |
|---|---|---|
| Mod names containing `/`, `\` or `..` are rejected (path traversal via `getConfigFolder`) | `core/src/mindustry/mod/Mods.java` | DONE |
| JS `require()` can no longer escape the mod's `scripts/` folder with `..`, `.`, absolute or drive paths | `core/src/mindustry/mod/Scripts.java` | DONE |
| Mod/script names are escaped before being put into generated JS (script injection) | `core/src/mindustry/mod/Scripts.java` | DONE |
| Client UUID/USID (admin credentials) now come from `SecureRandom` instead of a seeded `Random` | `core/Platform.java`, `core/NetClient.java` | DONE |
| Subnet bans only match on segment boundaries (`10.1` no longer bans `10.10.*`/`10.100.*`) | `core/src/mindustry/net/Administration.java` | DONE |
| Null player name is kicked cleanly instead of throwing an NPE mid-handshake | `core/src/mindustry/core/NetServer.java` | DONE |
| A non-whitelisted connection can no longer overwrite an existing admin's `adminUsid` | `core/src/mindustry/core/NetServer.java` | DONE |
| Server asset stream: count/size bounds (max 4096 assets, 64 MiB each) to stop hostile-server OOM | `core/src/mindustry/net/NetworkIO.java` | DONE |
| LAN discovery: ports > 32767 and negative gamemode bytes no longer read as negative | `core/src/mindustry/net/NetworkIO.java` | DONE |
| Save metadata stream is now closed on failure too (leaked handles lock save files on Windows) | `core/src/mindustry/io/SaveIO.java` | DONE |
| Bundle property readers closed after load (file handle leaks) | `mod/DataBundleLoader.java`, `mod/data/BundleAsset.java` | DONE |
| New regression tests for subnet matching and asset-size bounds | `tests/src/test/java/NetSecurityTests.java` | DONE |

## Phase 2 — Server hardening (DONE, verified)
1. **Player records**: read-only ban/admin/kick checks no longer create records. Null-name, invalid-identity, mod/version and other early handshake failures do not persist player records. Valid whitelist requests still record the identity so `whitelist add` continues to work.
2. **Tracking cleanup**: expire IP kicks and UUID encounter windows; maintenance runs at most once per minute during saves/connection checks. Optional `config playerInfoRetentionDays <days>` prunes inactive records. Default `0` preserves history; admins, bans, whitelist entries, active players and legacy records without timestamps are protected.
3. **UUID-change limit**: a five-minute connection cooldown replaces the permanent IP ban; tracking does not keep accumulating IDs while blocked.
4. **Handshake**: `worldSent` is a per-transfer acknowledgment latch, consumed before callbacks to prevent repeat/reentrant confirmation. Every completed transfer restores the player to the groups, including map reloads; initial join actions remain separate. Preserve the confirmation event's previous `hasConnected` semantics and revalidate the connection after plugin callbacks.
5. **Menus**: generated server sends track the exact recipients. Replies must correspond to an issued menu; validate option ranges, builder tokens/actions/value types and declared text limits before events/callbacks. Preserve same-ID callback reissuance, persistent follow-up dialogs and older visible builders when `hidePrevious=false`. Broadcast and targeted sends stay distinct. Only HTTP/HTTPS links with a host may be opened, still after the existing confirmation dialog.
6. **Command socket**: close client sockets/readers/writers, including blocked reads during disable; allow reconnect after resets. Synchronize lifecycle changes and publish the writer safely. Default bind address remains loopback; no authentication or external access is enabled.
7. **Updater**: handle missing release assets; require the GitHub release's SHA-256 digest and verify download length/hash before completion. Download into an attempt-owned temporary file, close resources and atomically replace the destination only after verification. Failed retries/cancellation remove only temporary files and preserve existing artifacts. This is integrity checking against trusted HTTPS metadata, not independent signature verification.

Compatibility notes: legacy menu replies have no nonce, so a delayed reply to a reissued identical menu ID is indistinguishable. Builder callers should use distinct tokens for concurrent/replaced dialogs. Previously hidden builders permit one late cancellation, never a stale action. Phase 3 parsing limits and Phase 4 concurrency work remain outstanding; this is not a claim that all security issues are resolved.

### Phase 2 verification
| Check | Result |
|---|---|
| Full `tests:test` | PASS: 366 tests, 0 failures, 0 errors, 0 skipped |
| `desktop:dist server:dist` | PASS: build exit 0; both JARs rebuilt |
| Regenerated `Call` menu hooks | PASS: separate broadcast and connection-targeted overloads |
| Isolated Windows client/server, menu update, map reload, saves and command socket | PASS: live join, both menu submissions, map reload, save, socket reset/reconnect/disable and invalid ports; client/server exit 0 |

Evidence: `../phase2-verified-build.log`, `../phase2-verified-windows.log`, `../phase2-evidence/`, and `../phase2-smoke/`. The failed repeat smoke attempt used an occupied game port; the fixture now checks both TCP and UDP availability without altering network settings.

## Phase 3 — Untrusted file parsing (DONE, verified on Windows)
- Save chunks are bounded to their declared region, reject negative/oversized/truncated lengths, and require exact consumption. Legacy short chunks and old building fields use the same bounded input; nested readers no longer share a mutable Reads object.
- Validate map dimensions, run-length spans, content/entity indices and section counts before allocations. Preview dimensions must agree with metadata; both pixmaps are disposed on parsing failures. Metadata/string counts retain their unsigned wire representation.
- Read embedded bytes incrementally rather than allocating a hostile declaration before EOF. Validate ContentAsset enum values while preserving recognized unsupported types and zero/missing-asset sentinels. Writers preflight file/text byte lengths.
- Align required asset IDs with the existing signed-short protocol: 32767, replacing Phase 2's overly narrow 4096. Enforce exact downloaded-asset totals, remaining-record headers and aggregate budgets; cancelled/replaced transfers never request the world or disconnect a replacement.
- Validate stream type/total before accumulation; reject overshoot, incomplete builds, too many active streams and excessive aggregate in-flight bytes. Empty streams complete without hanging. Texture length/removal framing is checked before decoding.
- Check PNG signature, first/unique IHDR, positive dimensions and chunk envelopes before native decoding. Preserve native PNG formats; DataAssetType explicitly supports PNG images. Atlas area arithmetic saturates safely; patch/upload pixmaps are disposed on errors. Locale-property readers use a bounded byte reader without changing escapes/continuations.
- Packet decompression validates header/flag/body size and actual LZ4 output length; direct/sliced buffers honor their bounds. A 32768-byte body no longer suffers signed-short copying. Reject invalid enums/array counts while preserving nullable -1 fields and missing references. Restore safe-object nested-array rules and correct ability payload skipping/invalid-plan consumption.

### Explicit safety budgets
These are resource policies, not claims that the old file format inherently supplied these limits; exceptionally large custom content may now be rejected with an explanatory error.
| Boundary | Limit |
|---|---|
| Save chunk / embedded binary asset / local property bundle | 256 MiB |
| Embedded patch/content text / downloaded asset / network texture | 64 MiB |
| Asset transfer and aggregate advertised in-flight streams | 256 MiB |
| Active incoming streams | 16 |
| Required/external asset IDs | 32767 |
| Map cells | 4194304; dimensions positive and representable by signed tile coordinates |
| Save entities / generic primitive array elements | 1000000; packet/chunk remaining bytes impose tighter bounds |
| Decoded packet body | 32768 bytes; actual encoded transport capacities remain unchanged |
| Patch/server PNG dimensions | Existing 2000-per-axis limit; editor PNG maps keep their existing 800-per-axis limit |

### Phase 3 verification
| Check | Result |
|---|---|
| Staged reader compilation and regression run | PASS: 190 tests, 0 failures, including 135 existing application/save tests |
| Full project tests and desktop/server JARs | PASS: 421 tests, 0 failures/errors/skipped; BUILD SUCCESSFUL in 12m 8s, exit 0; both JARs rebuilt (native PNG test fixed via `testRuntimeOnly natives-desktop`) |
| Isolated Windows asset download, PNG upload/rejection, menu updates, map reload and save reload | PASS: PHASE3_PLAYER_JOIN, MENU_STEP1, MENU_UPDATE, WORLD_RELOAD, TEXTURE, OVERSIZE_TEXTURE_REJECT, ASSET_DOWNLOAD, SAVE_RELOAD, SOCKET_RECONNECT_AND_DISABLE, INVALID_SOCKET_PORT; smoke exit 0 |

Evidence: `../phase3-staged-all-tests.log`, `../phase3-staging/applied.json`, `../phase3-verified-build.log`, `../phase3-verified-windows.log`, and `../phase3-smoke/`. Generic inferred-subtype entity-reference redesign and the Arc remote-address race remain outside this parsing pass; Phase 4 concurrency work and Phase 5 platform upgrades are not complete.

## Phase 4 — Concurrency
- `io/SaveFileReader.java:76-80`: static shared chunk buffers. Make them per-instance or `ThreadLocal`.
- `net/NetworkIO.java:124`: `writeRequiredAssets` iterates a Seq from another thread. Pass a copy.
- `ai/ControlPathfinder.java:126/1209/1376`: `fields` LongMap is shared across threads, with crashes hidden by catch blocks. Use a concurrent map or hand off on the main thread.
- `game/FogControl.java:340`: replace the empty catch with logging.

## Phase 5 — Toolchain
| Item | Current | Target | Status |
|---|---|---|---|
| Android Gradle Plugin | 8.2.2 (older than what officially supports Gradle 9 / compileSdk 36) | 9.x (latest 9.5.1) | BLOCKED: no Android SDK here to verify |
| RoboVM (iOS) | 2.3.26 | 3.0.0 | BLOCKED: needs macOS |
| JUnit | 5.14.4 | 6.x | PLANNED |
| kapt (maintenance mode) | Kotlin 2.3.20 kapt runs Java processors | plain `annotationProcessor`; then drop the Kotlin plugin | PLANNED (large change; generated-source paths move) |
| Annotation processor internals | `sun.reflect.annotation` plus javac `--add-exports` | public `javax.lang.model` APIs | PLANNED (most likely to break on JDK 25+) |
| Deprecated JDK APIs | `new URL(String)` (`BeControl.java:178`), `new Locale(...)` (`Vars.java:320-327`, `LanguageDialog.java`) | `URI.create(..).toURL()`, `Locale.of(..)` (once `--release` ≥ 19) | PLANNED |
| Gradle scripts | eager `task foo{}`, `buildDir`, `git ... .execute()` and file writes at configuration time (`preGen`) | `tasks.register`, `layout.buildDirectory`, providers so the configuration cache works | PLANNED |
| CI pins Arc to `master` | `git clone --branch=master Arc` | check out `archash` so CI matches local builds | PLANNED |
| `ModTestAllure` downloads a mod from GitHub at test time | network-dependent | cache or vendor the zip, tag the test `@Tag("network")` | PLANNED |

## Windows notes
- Needs JDK 17+ (`JAVA_HOME`). Java 8 is rejected by `settings.gradle` and `run-windows.bat`.
- If the wrapper times out on `release-assets.githubusercontent.com`, one GitHub CDN IP is unreachable from your network (Java doesn't fall back to the other addresses the way curl does). Download `gradle-9.3.1-bin.zip` manually and drop it into `%USERPROFILE%\.gradle\wrapper\dists\gradle-9.3.1-bin\<hash>\`. The wrapper checks it against the pinned SHA-256.
- Clone `https://github.com/Anuken/Arc` next to this folder (as CI does), because jitpack is unreliable.
