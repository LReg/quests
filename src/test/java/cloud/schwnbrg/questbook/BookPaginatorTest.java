package cloud.schwnbrg.questbook;

import static org.junit.jupiter.api.Assertions.*;

import cloud.schwnbrg.questbook.journal.BookPaginator;
import java.util.List;
import org.junit.jupiter.api.Test;

class BookPaginatorTest {

    @Test
    void wrapsWithinWidth() {
        for (String line : BookPaginator.wrap("The light went dark the night the gulls stopped crying and nobody ever came back to the shore")) {
            assertTrue(BookPaginator.width(line) <= BookPaginator.WIDTH, line);
        }
    }

    @Test
    void splitsOverlongWords() {
        List<String> lines = BookPaginator.wrap("W".repeat(60));
        assertTrue(lines.size() > 1);
        lines.forEach(l -> assertTrue(BookPaginator.width(l) <= BookPaginator.WIDTH));
    }

    @Test
    void pagesHaveAtMostFourteenLines() {
        List<String> paras = java.util.Collections.nCopies(40, "A short line of story text here");
        for (List<String> page : BookPaginator.paginate(paras)) assertTrue(page.size() <= BookPaginator.LINES);
        assertTrue(BookPaginator.paginate(paras).size() > 1);
    }

    @Test
    void emptyInputStillHasOnePage() {
        assertEquals(1, BookPaginator.paginate(List.of()).size());
    }

    @Test
    void blankParagraphIsKept() {
        assertEquals(List.of(""), BookPaginator.wrap(""));
    }
}
