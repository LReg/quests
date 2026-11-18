package cloud.schwnbrg.questbook.journal;

import java.util.ArrayList;
import java.util.List;

/** Splits plain text into book pages (14 lines x 114 px) with a simplified width table. */
public final class BookPaginator {

    public static final int LINES = 14;
    public static final int WIDTH = 114;

    private BookPaginator() {}

    public static int width(char c) {
        return switch (c) {
            case ' ' -> 4;
            case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 2;
            case 'l', '`' -> 3;
            case 't', 'I', '[', ']', '*', '"', '(', ')', '{', '}', '<', '>' -> 4;
            case 'f', 'k' -> 5;
            default -> 6;
        };
    }

    public static int width(String s) {
        int w = 0;
        for (char c : s.toCharArray()) w += width(c);
        return w;
    }

    /** Wraps a paragraph into lines no wider than {@link #WIDTH}; overlong words are split. */
    public static List<String> wrap(String paragraph) {
        List<String> out = new ArrayList<>();
        if (paragraph.isEmpty()) {
            out.add("");
            return out;
        }
        StringBuilder line = new StringBuilder();
        int lw = 0;
        for (String word : paragraph.split(" ", -1)) {
            int ww = width(word);
            int sp = line.length() == 0 ? 0 : 4;
            if (lw + sp + ww <= WIDTH) {
                if (sp > 0) line.append(' ');
                line.append(word);
                lw += sp + ww;
                continue;
            }
            if (line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                lw = 0;
            }
            if (ww <= WIDTH) {
                line.append(word);
                lw = ww;
            } else {
                for (char c : word.toCharArray()) {
                    int cw = width(c);
                    if (lw + cw > WIDTH) {
                        out.add(line.toString());
                        line.setLength(0);
                        lw = 0;
                    }
                    line.append(c);
                    lw += cw;
                }
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    /** Paginates paragraphs into pages; each page is a list of at most {@link #LINES} lines. */
    public static List<List<String>> paginate(List<String> paragraphs) {
        return paginate(paragraphs, LINES);
    }

    /** Like {@link #paginate(List)}, but the first page only has {@code firstPageLines} lines (room for a heading). */
    public static List<List<String>> paginate(List<String> paragraphs, int firstPageLines) {
        List<List<String>> pages = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String p : paragraphs) {
            for (String l : wrap(p)) {
                int cap = pages.isEmpty() ? Math.max(1, firstPageLines) : LINES;
                if (cur.size() >= cap) {
                    pages.add(cur);
                    cur = new ArrayList<>();
                }
                cur.add(l);
            }
        }
        if (!cur.isEmpty() || pages.isEmpty()) pages.add(cur);
        return pages;
    }
}
