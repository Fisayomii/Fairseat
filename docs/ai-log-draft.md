# AI log entries for FairSeat (draft: edit into your own words, then move into the team AI log)

Tool: Claude (claude.ai). Asked it to: draft the FairSeat contract v0.1.0, the Liquibase changesets,
sign-in and the seat rules, the tests, seed data, the README, and later the offer-window change and
the decision records. I applied every change myself as patches, reviewed each diff, and committed
it myself.

## Accepted
- The seat rule design (guarded UPDATE plus CHECK) and the ten-sign-up concurrency test. It was
  checked by the test going green in GitHub Actions.
- The rule that expiry is computed on reads and applied only on write paths or by the sweeper.

## Rejected or changed
- It proposed `DELETE /signups/{id}` for cancelling. I kept the cancelled sign-up visible instead
  and used `POST /signups/{id}/cancellation`, matching event cancellation.
- It offered to push to my repository. I refused, and took the changes as patch files so that
  every commit is mine.

## Output I checked and found wrong
1. **Checkstyle.** The generated code named the loggers `log`. `mvn verify` failed on our
   Checkstyle ConstantName rule (7 violations). I renamed them to `LOG`.
2. **A verification command.** It gave `Select-String -Pattern '\blog\.'` to prove the rename
   worked. That command listed 15 "leftover" lines, but every one already said `LOG`.
   `Select-String` ignores case by default, so the check itself was wrong. Re-running it with
   `-CaseSensitive` printed nothing.
3. **Seed data.** It said the seed would still show every awkward case on the day. The seed times
   are relative to the first deploy (25 Oct), so by 31 Oct Halima's offers and most upcoming events
   had become past events. My smoke test showed `/me/signups` empty for her. We added
   `tools/reset-demo.sql` to refresh the demo data before presenting.
4. **Editing an applied changeset.** The first version of the seed refresh edited
   `002-demo-seed.sql`. That changeset had already run on Neon. Liquibase checksums it, so the
   service would have refused to start. We kept 002 frozen and put the new values only in the reset
   script.
