package cloud.schwnbrg.questbook.model;

/** "If {@code cond} → go to {@code target}". A step's rules are checked top to bottom when it is done; the first true one wins. */
public record Rule(Cond cond, Target target) {}
