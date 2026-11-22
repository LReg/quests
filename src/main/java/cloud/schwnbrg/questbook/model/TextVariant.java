package cloud.schwnbrg.questbook.model;

import java.util.List;

/** Text told instead of the step's normal text when {@code cond} is true (first true variant wins). */
public record TextVariant(Cond cond, List<String> text) {
    public TextVariant {
        text = text == null ? List.of() : List.copyOf(text);
    }
}
