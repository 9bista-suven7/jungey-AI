package dev.suven.jungeynotepad.ui;

import dev.suven.jungeynotepad.notes.Markdown;
import dev.suven.jungeynotepad.notes.Markdown.Block;
import dev.suven.jungeynotepad.notes.Markdown.Span;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The note as it reads: headings, lists, quotes and code drawn, the marks gone. Ticking a
 * box ticks it in the text, clicking a heading takes the editor to it, and links open in
 * the browser.
 */
final class Preview extends ScrollPane {

    private final VBox page = new VBox(6);
    private final IntConsumer onToggle;
    private final IntConsumer onJump;
    private final Consumer<String> onLink;

    Preview(IntConsumer onToggle, IntConsumer onJump, Consumer<String> onLink) {
        this.onToggle = onToggle;
        this.onJump = onJump;
        this.onLink = onLink;
        page.getStyleClass().add("preview-page");
        page.setPadding(new Insets(26, 34, 40, 34));
        setContent(page);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        getStyleClass().add("preview");
    }

    void show(String text) {
        double at = getVvalue();
        List<Node> nodes = new ArrayList<>();
        int number = 0;
        for (Block b : Markdown.parse(text)) {
            number = b.kind() == Markdown.Kind.NUMBERED ? number + 1 : 0;
            Node n = switch (b.kind()) {
                case HEADING -> heading(b);
                case PARAGRAPH -> flow(b.spans(), "pv-p");
                case BULLET -> item(b, bullet("•"));
                case NUMBERED -> item(b, bullet(b.marker() + "."));
                case TASK -> task(b);
                case QUOTE -> boxed(flow(b.spans(), "pv-p"), "pv-quote");
                case CODE -> code(b);
                case RULE -> rule();
                case BLANK -> null;
            };
            if (n != null) nodes.add(n);
        }
        if (nodes.isEmpty()) {
            Label empty = new Label("Nothing to show yet.");
            empty.getStyleClass().add("pv-empty");
            nodes.add(empty);
        }
        page.getChildren().setAll(nodes);
        // Keep the reader's place while the text changes under them.
        layout();
        setVvalue(at);
    }

    private Node heading(Block b) {
        TextFlow f = flow(b.spans(), "pv-h" + Math.min(b.level(), 3));
        f.setCursor(Cursor.HAND);
        f.setOnMouseClicked(e -> onJump.accept(b.line()));
        VBox.setMargin(f, new Insets(b.level() == 1 ? 6 : 12, 0, 2, 0));
        return f;
    }

    private Node item(Block b, Node mark) {
        HBox row = new HBox(8, mark, grow(flow(b.spans(), "pv-p")));
        row.setPadding(new Insets(0, 0, 0, 4 + b.level() * 22));
        return row;
    }

    private Node task(Block b) {
        CheckBox box = new CheckBox();
        box.setSelected(b.done());
        box.setOnAction(e -> onToggle.accept(b.line()));
        TextFlow text = flow(b.spans(), b.done() ? "pv-done" : "pv-p");
        HBox row = new HBox(8, box, grow(text));
        row.setPadding(new Insets(0, 0, 0, b.level() * 22));
        return row;
    }

    private static Label bullet(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("pv-bullet");
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    private static Node grow(TextFlow f) {
        HBox.setHgrow(f, Priority.ALWAYS);
        return f;
    }

    private static Node boxed(Node inner, String style) {
        VBox box = new VBox(inner);
        box.getStyleClass().add(style);
        return box;
    }

    private static Node code(Block b) {
        Text t = new Text(b.raw());
        t.getStyleClass().add("pv-code-text");
        TextFlow f = new TextFlow(t);
        return boxed(f, "pv-codeblock");
    }

    private static Node rule() {
        Region r = new Region();
        r.getStyleClass().add("pv-rule");
        VBox.setMargin(r, new Insets(8, 0, 8, 0));
        return r;
    }

    private TextFlow flow(List<Span> spans, String style) {
        TextFlow f = new TextFlow();
        f.getStyleClass().add(style);
        for (Span s : spans) {
            Text t = new Text(s.text());
            t.getStyleClass().add("pv-text");
            if (s.bold()) t.getStyleClass().add("pv-bold");
            if (s.italic()) t.getStyleClass().add("pv-italic");
            if (s.code()) t.getStyleClass().add("pv-code");
            if (s.strike()) t.setStrikethrough(true);
            if (s.tag()) t.getStyleClass().add("pv-tag");
            if (s.link() != null) {
                t.getStyleClass().add("pv-link");
                t.setCursor(Cursor.HAND);
                t.setOnMouseClicked(e -> onLink.accept(s.link()));
            }
            f.getChildren().add(t);
        }
        return f;
    }
}
