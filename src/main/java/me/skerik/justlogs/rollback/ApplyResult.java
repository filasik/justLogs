package me.skerik.justlogs.rollback;

/** Outcome of applying a plan: things changed, things that could not be changed, chunks worked on. */
public record ApplyResult(int changed, int skipped, int chunks) {
}
