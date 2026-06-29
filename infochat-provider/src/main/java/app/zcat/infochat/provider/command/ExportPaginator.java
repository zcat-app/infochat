package app.zcat.infochat.provider.command;

import app.zcat.infochat.core.util.JsonEscaper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Pure-function utility that splits an ordered table-to-rows map into
 * a list of valid JSON object strings, each fitting within the given
 * page cap (soft at row granularity — a single row larger than the cap
 * occupies its own page).
 *
 * <p>Row order within each table is preserved. A table whose rows span
 * two or more pages appears as a key in each page with its respective
 * row subset. The union across all returned pages equals the full
 * input.
 */
public final class ExportPaginator {

    private ExportPaginator() {}

    /**
     * @param tableRows ordered map from table name to its list of row
     *                  JSON fragments (each entry is a valid JSON
     *                  object string representing one row)
     * @param pageCap   maximum byte length per page; a single row
     *                  exceeding this cap occupies its own page
     * @return list of valid JSON object strings; never empty if the
     *         input contains at least one table (empty tables produce
     *         empty arrays in the output)
     */
    public static List<String> paginate(
            LinkedHashMap<String, List<String>> tableRows,
            int pageCap) {

        List<String> pages = new ArrayList<>();
        LinkedHashMap<String, List<String>> currentPage = new LinkedHashMap<>();

        for (Map.Entry<String, List<String>> entry : tableRows.entrySet()) {
            String table = entry.getKey();
            List<String> rows = entry.getValue();

            if (rows.isEmpty()) {
                // Empty tables still appear in the output as empty arrays.
                // They cost only the key overhead; add to current page.
                currentPage.put(table, new ArrayList<>());
                continue;
            }

            for (String row : rows) {
                int costIfAdded = estimateSizeWithRow(currentPage, table, row);

                if (costIfAdded > pageCap && !isPageEmpty(currentPage)) {
                    // Flush current page before adding this row.
                    pages.add(renderJson(currentPage));
                    currentPage = new LinkedHashMap<>();
                }

                // Add the row (may start a new page or extend current).
                currentPage.computeIfAbsent(table, k -> new ArrayList<>()).add(row);
            }
        }

        // Flush remaining content.
        if (!isPageEmpty(currentPage)) {
            pages.add(renderJson(currentPage));
        }

        // If input was entirely empty tables, we still need one page.
        if (pages.isEmpty() && !tableRows.isEmpty()) {
            pages.add(renderJson(currentPage));
        }

        return pages;
    }

    private static boolean isPageEmpty(LinkedHashMap<String, List<String>> page) {
        for (List<String> rows : page.values()) {
            if (!rows.isEmpty()) {
                return false;
            }
        }
        // A page with only empty-array tables counts as empty for
        // flushing purposes — we don't want a page break between two
        // tables that both have zero rows.
        return page.isEmpty() || page.values().stream().allMatch(List::isEmpty);
    }

    /**
     * Estimate the byte cost if {@code row} were added under
     * {@code table} in the current page, without mutating the page.
     */
    private static int estimateSizeWithRow(
            LinkedHashMap<String, List<String>> page,
            String table,
            String row) {

        // Temporarily add, measure, remove.
        List<String> existing = page.get(table);
        boolean newKey = (existing == null);
        if (existing == null) {
            existing = new ArrayList<>();
            page.put(table, existing);
        }
        existing.add(row);
        int size = estimateSize(page);
        existing.remove(existing.size() - 1);
        if (newKey) {
            page.remove(table);
        }
        return size;
    }

    /**
     * Estimate the UTF-8 byte length of the JSON rendering of this
     * page. Uses the actual render for accuracy — the pages are small
     * (bounded by pageCap) so this is cheap.
     */
    static int estimateSize(LinkedHashMap<String, List<String>> page) {
        return renderJson(page).length();
    }

    /**
     * Render the page as a JSON object: keys are table names, values
     * are JSON arrays of row objects. Output is compact (no pretty
     * printing) for size efficiency.
     */
    static String renderJson(LinkedHashMap<String, List<String>> page) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean firstTable = true;
        for (Map.Entry<String, List<String>> entry : page.entrySet()) {
            if (!firstTable) {
                sb.append(',');
            }
            firstTable = false;
            sb.append('"').append(JsonEscaper.escape(entry.getKey())).append("\":[");
            StringJoiner joiner = new StringJoiner(",");
            for (String row : entry.getValue()) {
                joiner.add(row);
            }
            sb.append(joiner).append(']');
        }
        sb.append('}');
        return sb.toString();
    }

}
