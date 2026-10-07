package com.miko.orderbook;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class OrderBookApp extends Application {
    private static final int LEVELS = 8;
    private static final int TAPE_SIZE = 18;
    private static final int ACTIVITY_SIZE = 8;
    private static final double TICKS_PER_SECOND = 10.0;   // timeline fires every 100 ms
    private static final double CHART_WINDOW_SECONDS = 30;

    private static final String MONO = "-fx-font-family: 'Consolas', monospace; -fx-font-size: 14px;";
    private static final String ASK_COLOR = "#ff5c5c";    // bright red
    private static final String BID_COLOR = "#3ddc84";    // bright green
    private static final String FAIR_COLOR = "#ffffff";   // white
    private static final String LAST_COLOR = "#ffd54f";   // yellow
    private static final String GRAY = "#888888";
    private static final String MINE_BG = "#1f3a5f";      // dark blue: levels with my orders

    private OrderBook book;
    private MatchingEngine engine;
    private FairPrice fairPrice;
    private OrderFlowGenerator flow;
    private IdGenerator ids;          // shared by the simulator and manual orders

    private double eventsPerSecond = 50;
    private double eventBudget = 0;   // fractional events carried over between ticks
    private double simTime = 0;       // simulated seconds since start
    private Timeline timeline;

    private final VBox ladder = new VBox(2);
    private final VBox tape = new VBox(2);
    private final Label status = new Label();

    // --- my orders ---
    private final Set<Long> myIds = new HashSet<>();                    // every manual order ever sent
    private final Map<Long, HBox> myOrderRows = new LinkedHashMap<>();  // resting manual orders → their UI row
    private final VBox myOrdersBox = new VBox(4);
    private final Deque<String> activity = new ArrayDeque<>();          // newest first
    private final VBox activityBox = new VBox(2);
    private int lastSeenTrade = 0;                                      // trade log index already scanned

    // --- chart ---
    private final NumberAxis timeAxis = new NumberAxis();
    private final NumberAxis priceAxis = new NumberAxis();
    private LineChart<Number, Number> chart;
    private final XYChart.Series<Number, Number> askSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> bidSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> fairSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> lastSeries = new XYChart.Series<>();

    @Override
    public void start(Stage stage) {
        // --- simulation ---
        Random random = new Random(42);
        book = new OrderBook();
        engine = new MatchingEngine(book);
        fairPrice = new FairPrice(10000, 0.5, random);
        ids = new IdGenerator();
        flow = new OrderFlowGenerator(engine, fairPrice, random, 0.2, ids);

        // --- controls ---
        Button pauseButton = new Button("Pause");
        pauseButton.setOnAction(e -> {
            if (timeline.getStatus() == Animation.Status.RUNNING) {
                timeline.pause();
                pauseButton.setText("Start");
            } else {
                timeline.play();
                pauseButton.setText("Pause");
            }
        });

        // logarithmic speed: slider holds log10(events/s), range 1/s .. 500/s
        Label speedLabel = new Label(formatSpeed(eventsPerSecond));
        Slider speedSlider = new Slider(0, Math.log10(500), Math.log10(eventsPerSecond));
        speedSlider.setPrefWidth(200);
        speedSlider.valueProperty().addListener((obs, oldV, newV) -> {
            eventsPerSecond = Math.pow(10, newV.doubleValue());
            speedLabel.setText(formatSpeed(eventsPerSecond));
        });

        Label aggLabel = new Label("0.20");
        Slider aggSlider = new Slider(0.0, 0.8, 0.2);
        aggSlider.valueProperty().addListener((obs, oldV, newV) -> {
            flow.setAggressiveness(newV.doubleValue());
            aggLabel.setText(String.format("%.2f", newV.doubleValue()));
        });

        HBox controls = new HBox(10,
                pauseButton,
                new Label("Speed"), speedSlider, speedLabel,
                new Label("Aggressiveness"), aggSlider, aggLabel);
        controls.setAlignment(Pos.CENTER_LEFT);

        // --- layout ---
        status.setStyle(MONO);
        VBox top = new VBox(8, controls, status);

        ladder.setMinWidth(300);

        VBox tapeBox = new VBox(6, header("TRADES"), tape);
        tapeBox.setMinWidth(260);

        VBox chartBox = buildChart();
        VBox orderEntry = buildOrderEntry();

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setLeft(ladder);
        root.setCenter(chartBox);
        root.setRight(tapeBox);
        root.setBottom(orderEntry);
        root.setPadding(new Insets(12));
        BorderPane.setMargin(top, new Insets(0, 0, 10, 0));
        BorderPane.setMargin(chartBox, new Insets(0, 16, 0, 16));
        BorderPane.setMargin(orderEntry, new Insets(12, 0, 0, 0));

        // dark theme: Modena derives all control and default text colors from these
        root.setStyle("-fx-base: #1e1e1e; -fx-background: #000000;");

        // --- timer: step + redraw every 100 ms ---
        timeline = new Timeline(new KeyFrame(Duration.millis(1000 / TICKS_PER_SECOND), e -> tick()));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(root,Math.min(1300, screen.getWidth()), Math.min(850, screen.getHeight()));
        scene.setFill(Color.BLACK);
        stage.setTitle("Order Book Simulator");
        stage.setScene(scene);
        stage.show();

        styleChart();   // after show(): the chart's internal nodes exist only once it's on screen
        refresh();
    }

    // ---------- manual order entry ----------
    private VBox buildOrderEntry() {
        ComboBox<Side> sideBox = new ComboBox<>();
        sideBox.getItems().addAll(Side.values());
        sideBox.setValue(Side.BUY);

        ComboBox<OrderType> typeBox = new ComboBox<>();
        typeBox.getItems().addAll(OrderType.values());
        typeBox.setValue(OrderType.LIMIT);

        TextField priceField = new TextField();
        priceField.setPromptText("e.g. 99.75");
        priceField.setPrefWidth(110);

        TextField qtyField = new TextField("10");
        qtyField.setPrefWidth(70);

        // market orders have no price: grey out the field
        typeBox.valueProperty().addListener((obs, oldV, newV) ->
                priceField.setDisable(newV == OrderType.MARKET));

        Label error = new Label();
        error.setStyle("-fx-text-fill: " + ASK_COLOR + ";");

        Button submit = new Button("Submit");
        submit.setOnAction(e -> {
            String problem = submitManualOrder(sideBox.getValue(), typeBox.getValue(),
                    priceField.getText(), qtyField.getText());
            error.setText(problem == null ? "" : problem);
        });

        HBox form = new HBox(10,
                header("MY ORDER"), sideBox, typeBox,
                new Label("price"), priceField,
                new Label("qty"), qtyField,
                submit, error);
        form.setAlignment(Pos.CENTER_LEFT);

        VBox mine = new VBox(6, header("MY RESTING ORDERS"), myOrdersBox);
        mine.setMinWidth(560);
        VBox act = new VBox(6, header("MY ACTIVITY"), activityBox);

        return new VBox(10, form, new HBox(30, mine, act));
    }

    // Returns an error message, or null if the order was sent.
    private String submitManualOrder(Side side, OrderType type, String priceText, String qtyText) {
        long qty;
        try {
            qty = Long.parseLong(qtyText.trim());
        } catch (NumberFormatException ex) {
            return "qty must be a whole number";
        }
        if (qty <= 0) {
            return "qty must be > 0";
        }

        long priceTicks = 0;   // ignored for MARKET
        if (type == OrderType.LIMIT) {
            double price;
            try {
                price = Double.parseDouble(priceText.trim());
            } catch (NumberFormatException ex) {
                return "price must be a number like 99.75";
            }
            if (price <= 0) {
                return "price must be > 0";
            }
            priceTicks = Math.round(price * 100);   // the ONE place decimals become ticks
            if (Math.abs(price * 100 - priceTicks) > 1e-6) {
                return "price must be a multiple of 0.01 (one tick)";
            }
        }

        long id = ids.next();
        Order order = new Order(id, side, type, priceTicks, qty, id);
        ExecutionReport report = engine.execute(order);
        myIds.add(id);
        lastSeenTrade = engine.getTradeLog().size();   // these fills are reported here, not by the scan

        logActivity(describe(order, report));
        if (book.contains(id)) {
            addMyOrderRow(id);
        }
        refresh();
        return null;
    }

    private String describe(Order order, ExecutionReport r) {
        String what = String.format("#%d %s %s %d%s",
                order.getId(), order.getSide(), order.getType(), order.getOriginalQty(),
                order.getType() == OrderType.LIMIT ? " @ " + BookPrinter.formatPrice(order.getPrice()) : "");

        if (r.filledQty() == 0) {
            return what + (book.contains(order.getId()) ? " → resting" : " → nothing to trade against");
        }
        String text = String.format("%s → filled %d, avg %.3f", what, r.filledQty(), r.avgPrice() / 100.0);
        if (r.slippageTicks() != null) {
            text += String.format(", slippage %.2f ticks", r.slippageTicks());
        }
        if (book.contains(order.getId())) {
            text += ", " + order.getRemainingQty() + " resting";
        }
        return text;
    }

    // Each tick: look at trades that happened since last time, report the ones involving my orders.
    private void scanMyFills() {
        List<Trade> log = engine.getTradeLog();
        for (int i = lastSeenTrade; i < log.size(); i++) {
            Trade t = log.get(i);
            if (myIds.contains(t.buyOrderId())) {
                logActivity(String.format("FILL #%d bought %d @ %s",
                        t.buyOrderId(), t.quantity(), BookPrinter.formatPrice(t.price())));
            }
            if (myIds.contains(t.sellOrderId())) {
                logActivity(String.format("FILL #%d sold %d @ %s",
                        t.sellOrderId(), t.quantity(), BookPrinter.formatPrice(t.price())));
            }
        }
        lastSeenTrade = log.size();
    }

    private void logActivity(String text) {
        activity.addFirst(text);
        while (activity.size() > ACTIVITY_SIZE) {
            activity.removeLast();
        }
    }

    // Created ONCE per order (so its Cancel button stays clickable), then only its text is updated.
    private void addMyOrderRow(long id) {
        Label info = new Label();
        info.setStyle(MONO);
        Button cancel = new Button("Cancel");
        cancel.setOnAction(e -> {
            if (book.cancel(id)) {
                logActivity("#" + id + " cancelled");
            }
            refresh();
        });
        HBox row = new HBox(10, cancel, info);
        row.setAlignment(Pos.CENTER_LEFT);
        myOrderRows.put(id, row);
        myOrdersBox.getChildren().add(row);
    }

    private void refreshMyOrders() {
        Iterator<Map.Entry<Long, HBox>> it = myOrderRows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, HBox> entry = it.next();
            QueuePosition qp = book.queuePosition(entry.getKey());

            if (qp == null) {   // filled or cancelled → remove its row
                myOrdersBox.getChildren().remove(entry.getValue());
                it.remove();
                continue;
            }

            Label info = (Label) entry.getValue().getChildren().get(1);
            info.setText(String.format("#%d %s %d @ %s | position %d | %d ahead",
                    entry.getKey(), qp.side(), qp.remainingQty(),
                    BookPrinter.formatPrice(qp.price()), qp.position(), qp.qtyAhead()));
            info.setStyle(MONO + "-fx-text-fill: " + (qp.side() == Side.BUY ? BID_COLOR : ASK_COLOR) + ";");
        }
    }

    private void refreshActivity() {
        List<Label> lines = new ArrayList<>();
        for (String text : activity) {
            Label line = new Label(text);
            line.setStyle(MONO + "-fx-font-size: 13px;");
            lines.add(line);
        }
        activityBox.getChildren().setAll(lines);
    }

    // ---------- chart ----------

    private VBox buildChart() {
        timeAxis.setAutoRanging(false);      // we scroll the window ourselves
        timeAxis.setLowerBound(0);
        timeAxis.setUpperBound(CHART_WINDOW_SECONDS);
        timeAxis.setTickUnit(5);
        timeAxis.setTickLabelFill(Color.GRAY);

        priceAxis.setAutoRanging(true);
        priceAxis.setForceZeroInRange(false);   // zoom in on ~100, don't start at 0
        priceAxis.setTickLabelFill(Color.GRAY);

        chart = new LineChart<>(timeAxis, priceAxis);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);    // lines only, no dot per point
        chart.setLegendVisible(false);    // custom legend below matches our colors
        chart.getData().add(askSeries);
        chart.getData().add(bidSeries);
        chart.getData().add(fairSeries);
        chart.getData().add(lastSeries);

        styleSeries(askSeries, ASK_COLOR, false);
        styleSeries(bidSeries, BID_COLOR, false);
        styleSeries(fairSeries, FAIR_COLOR, true);
        styleSeries(lastSeries, LAST_COLOR, false);

        HBox legend = new HBox(16,
                legendItem("best ask", ASK_COLOR),
                legendItem("best bid", BID_COLOR),
                legendItem("fair (hidden)", FAIR_COLOR),
                legendItem("last trade", LAST_COLOR));

        VBox box = new VBox(6, legend, chart);
        VBox.setVgrow(chart, Priority.ALWAYS);   // chart takes all remaining height
        return box;
    }

    private void styleSeries(XYChart.Series<Number, Number> series, String color, boolean dashed) {
        String style = "-fx-stroke: " + color + "; -fx-stroke-width: 1.5px;";
        if (dashed) {
            style += " -fx-stroke-dash-array: 6 4;";
        }
        series.getNode().setStyle(style);
    }

    private void styleChart() {
        Node plot = chart.lookup(".chart-plot-background");
        if (plot != null) {
            plot.setStyle("-fx-background-color: #0a0a0a;");
        }
        chart.lookupAll(".chart-horizontal-grid-lines").forEach(n -> n.setStyle("-fx-stroke: #222222;"));
        chart.lookupAll(".chart-vertical-grid-lines").forEach(n -> n.setStyle("-fx-stroke: #222222;"));
        chart.setAlternativeRowFillVisible(false);
        chart.setAlternativeColumnFillVisible(false);
    }

    private Label legendItem(String text, String color) {
        Label label = new Label("■ " + text);
        label.setStyle(MONO + "-fx-font-size: 12px; -fx-text-fill: " + color + ";");
        return label;
    }

    private void recordChartPoint() {
        addPoint(fairSeries, fairPrice.getValue() / 100.0);

        Long bid = book.bestBid();
        if (bid != null) {
            addPoint(bidSeries, bid / 100.0);
        }
        Long ask = book.bestAsk();
        if (ask != null) {
            addPoint(askSeries, ask / 100.0);
        }
        List<Trade> log = engine.getTradeLog();
        if (!log.isEmpty()) {
            addPoint(lastSeries, log.get(log.size() - 1).price() / 100.0);
        }

        // scroll the x-axis: show the last CHART_WINDOW_SECONDS
        timeAxis.setLowerBound(Math.max(0, simTime - CHART_WINDOW_SECONDS));
        timeAxis.setUpperBound(Math.max(CHART_WINDOW_SECONDS, simTime));
    }

    private void addPoint(XYChart.Series<Number, Number> series, double price) {
        series.getData().add(new XYChart.Data<>(simTime, price));
        // drop points that scrolled out of the window
        while (!series.getData().isEmpty()
                && series.getData().get(0).getXValue().doubleValue() < simTime - CHART_WINDOW_SECONDS) {
            series.getData().remove(0);
        }
    }

    // ---------- simulation loop ----------

    private static String formatSpeed(double perSecond) {
        return perSecond < 10
                ? String.format("%.1f/s", perSecond)
                : String.format("%.0f/s", perSecond);
    }

    // accumulator: add a fraction of an event each tick, run whole events when the budget allows
    private void tick() {
        eventBudget += eventsPerSecond / TICKS_PER_SECOND;
        while (eventBudget >= 1.0) {
            flow.step();
            eventBudget -= 1.0;
        }
        scanMyFills();
        simTime += 1.0 / TICKS_PER_SECOND;
        recordChartPoint();
        refresh();
    }

    // ---------- drawing ----------

    private void refresh() {
        refreshStatus();
        refreshMyOrders();
        refreshLadder();
        refreshTape();
        refreshActivity();
    }

    private void refreshStatus() {
        List<Trade> log = engine.getTradeLog();
        String last = log.isEmpty()
                ? "-"
                : BookPrinter.formatPrice(log.get(log.size() - 1).price());
        status.setText(String.format("fair %.2f | last %s | trades %d",
                fairPrice.getValue() / 100.0, last, log.size()));
    }

    private void refreshLadder() {
        // prices where I have resting orders (a price can only rest on one side, so one set is enough)
        Set<Long> myPrices = new HashSet<>();
        for (long id : myOrderRows.keySet()) {
            QueuePosition qp = book.queuePosition(id);
            if (qp != null) {
                myPrices.add(qp.price());
            }
        }

        List<HBox> rows = new ArrayList<>();

        List<DepthLevel> asks = new ArrayList<>(book.depth(Side.SELL, LEVELS));
        Collections.reverse(asks);
        for (DepthLevel level : asks) {
            rows.add(row("ASK", level, ASK_COLOR, myPrices.contains(level.price())));
        }

        rows.add(middleRow());

        for (DepthLevel level : book.depth(Side.BUY, LEVELS)) {
            rows.add(row("BID", level, BID_COLOR, myPrices.contains(level.price())));
        }

        ladder.getChildren().setAll(rows);
    }

    private void refreshTape() {
        List<Trade> log = engine.getTradeLog();
        List<Label> lines = new ArrayList<>();

        int oldest = Math.max(0, log.size() - TAPE_SIZE);
        for (int i = log.size() - 1; i >= oldest; i--) {   // newest first
            Trade t = log.get(i);

            // tick rule: compare with the previous trade's price
            String color = GRAY;
            if (i > 0) {
                long prevPrice = log.get(i - 1).price();
                if (t.price() > prevPrice) {
                    color = BID_COLOR;      // uptick: likely buyer-initiated
                } else if (t.price() < prevPrice) {
                    color = ASK_COLOR;      // downtick: likely seller-initiated
                }
            }

            Label line = new Label(String.format("#%-6d %4d @ %s",
                    t.sequence(), t.quantity(), BookPrinter.formatPrice(t.price())));
            line.setStyle(MONO + "-fx-text-fill: " + color + ";");
            lines.add(line);
        }

        tape.getChildren().setAll(lines);
    }

    private HBox row(String side, DepthLevel level, String color, boolean mine) {
        HBox row = new HBox(8,
                cell(mine ? "★" + side : side, 50, color),
                cell(BookPrinter.formatPrice(level.price()), 80, color),
                cell(String.valueOf(level.totalQty()), 70, color),
                cell(String.valueOf(level.orderCount()), 50, color));
        if (mine) {
            row.setStyle("-fx-background-color: " + MINE_BG + ";");
        }
        return row;
    }

    private HBox middleRow() {
        Long spread = book.spread();
        Double mid = book.mid();
        String text = spread == null
                ? "— one side empty —"
                : String.format("— spread %s | mid %.3f —", BookPrinter.formatPrice(spread), mid / 100.0);
        Label label = new Label(text);
        label.setStyle(MONO + "-fx-text-fill: " + GRAY + ";");
        return new HBox(label);
    }

    private Label cell(String text, double width, String color) {
        Label label = new Label(text);
        label.setMinWidth(width);
        label.setAlignment(Pos.CENTER_RIGHT);
        label.setStyle(MONO + "-fx-text-fill: " + color + ";");
        return label;
    }

    private Label header(String text) {
        Label label = new Label(text);
        label.setStyle(MONO + "-fx-font-weight: bold;");
        return label;
    }

    public static void main(String[] args) {
        launch(args);
    }
}