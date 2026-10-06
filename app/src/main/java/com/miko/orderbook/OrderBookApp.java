package com.miko.orderbook;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class OrderBookApp extends Application {
    private static final int LEVELS = 10;

    private static final String MONO = "-fx-font-family: 'Consolas', monospace; -fx-font-size: 14px;";
    private static final String ASK_COLOR = "#d9534f";   // red
    private static final String BID_COLOR = "#2e9d4f";   // green

    private OrderBook book;
    private FairPrice fairPrice;
    private OrderFlowGenerator flow;

    private int eventsPerTick = 5;   // 5 events per 100 ms tick = 50/s
    private Timeline timeline;       // field, so the Pause button can reach it

    private final VBox ladder = new VBox(2);   // rows stacked vertically, 2px apart
    private final Label status = new Label();

    @Override
    public void start(Stage stage) {
        // --- simulation (same setup as App.main) ---
        Random random = new Random(42);
        book = new OrderBook();
        MatchingEngine engine = new MatchingEngine(book);
        fairPrice = new FairPrice(10000, 0.5, random);
        flow = new OrderFlowGenerator(engine, fairPrice, random, 0.2);

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

        Label speedLabel = new Label("50/s");
        Slider speedSlider = new Slider(10, 500, 50);   // min, max, initial
        speedSlider.valueProperty().addListener((obs, oldV, newV) -> {
            int perSecond = newV.intValue();
            eventsPerTick = Math.max(1, perSecond / 10);   // 10 ticks per second
            speedLabel.setText(perSecond + "/s");
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

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(ladder);
        root.setPadding(new Insets(12));
        BorderPane.setMargin(top, new Insets(0, 0, 10, 0));

        // --- timer: step + redraw every 100 ms ---
        timeline = new Timeline(new KeyFrame(Duration.millis(100), e -> tick()));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();

        stage.setTitle("Order Book Simulator");
        stage.setScene(new Scene(root, 720, 600));
        stage.show();
        refresh();
    }

    private void tick() {
        for (int i = 0; i < eventsPerTick; i++) {
            flow.step();
        }
        refresh();
    }

    private void refresh() {
        status.setText(String.format("fair %.2f", fairPrice.getValue() / 100.0));

        List<HBox> rows = new ArrayList<>();

        // asks, reversed so the best ask sits right above the spread
        List<DepthLevel> asks = new ArrayList<>(book.depth(Side.SELL, LEVELS));
        Collections.reverse(asks);
        for (DepthLevel level : asks) {
            rows.add(row("ASK", level, ASK_COLOR));
        }

        // spread / mid line
        rows.add(middleRow());

        // bids, best first
        for (DepthLevel level : book.depth(Side.BUY, LEVELS)) {
            rows.add(row("BID", level, BID_COLOR));
        }

        ladder.getChildren().setAll(rows);   // replace the old rows with the new ones
    }

    private HBox row(String side, DepthLevel level, String color) {
        return new HBox(8,
                cell(side, 40, color),
                cell(BookPrinter.formatPrice(level.price()), 80, color),
                cell(String.valueOf(level.totalQty()), 70, color),
                cell(String.valueOf(level.orderCount()), 50, color));
    }

    private HBox middleRow() {
        Long spread = book.spread();
        Double mid = book.mid();
        String text = spread == null
                ? "— one side empty —"
                : String.format("— spread %s | mid %.3f —", BookPrinter.formatPrice(spread), mid / 100.0);
        Label label = new Label(text);
        label.setStyle(MONO + "-fx-text-fill: #888888;");
        return new HBox(label);
    }

    private Label cell(String text, double width, String color) {
        Label label = new Label(text);
        label.setMinWidth(width);
        label.setAlignment(Pos.CENTER_RIGHT);
        label.setStyle(MONO + "-fx-text-fill: " + color + ";");
        return label;
    }

    public static void main(String[] args) {
        launch(args);
    }
}