import com.example.datasail.core.*;
import java.sql.Types;
import java.util.List;

/** Synthetic offline reproduction against the compiled production filter compiler. */
public class FilterBoundaryProbe {
    public static void main(String[] args) {
        var table = new TableRef("synthetic", "public", "records", 1, false);
        var column = new ColumnDef("id", Types.BIGINT, "BIGINT UNSIGNED", false, 20, 0, false, false, 1, false);
        var schema = new TableSchema(table, List.of(column), false, null);
        var view = new TableView(List.of(new ColumnFilter("id", FilterOperator.EQ, "18446744073709551615")), List.of());
        try {
            TableViews.INSTANCE.compile(schema, view, name -> "`" + name + "`", false, false);
            throw new AssertionError("Expected current V0.11.0 unsigned range limitation");
        } catch (WorkspaceRejected error) {
            System.out.println("CONFIRMED: valid BIGINT UNSIGNED maximum rejected: " + error.getExplanation());
        }
        var textColumn = new ColumnDef("body", Types.LONGVARCHAR, "text", true, 10000, 0, false, false, 0, false);
        var textSchema = new TableSchema(table, List.of(column, textColumn), false, null);
        var sort = new TableView(List.of(), List.of(new ColumnSort("body", false)));
        var compiled = TableViews.INSTANCE.compile(textSchema, sort, name -> "[" + name + "]", false, true);
        if (!compiled.getOrder().contains("[body] ASC")) throw new AssertionError("Unexpected compiler result");
        System.out.println("CONFIRMED: compiler accepts SQL Server text ORDER BY: " + compiled.getOrder());
        System.out.println("No database connection used; server rejection of text sorting not exercised here.");
    }
}
