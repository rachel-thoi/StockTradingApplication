package com.example.demo;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class OptionsAnalyzerApp extends Application {

    // Input controls
    private TextField stockSymbolField;
    private DatePicker expirationDatePicker;
    private ComboBox<String> optionTypeCombo;
    private TextField riskFreeRateField;
    private TextField volatilityField;

    // Action controls
    private Button executeButton;
    private Button clearButton;
    private Button installDepsButton;
    private ProgressIndicator progressIndicator;
    private Label statusLabel;

    // Display controls
    private ImageView chartImageView;
    private Label placeholderLabel;
    private TextArea outputArea;

    // Instance variables
    private PythonExecutor pythonExecutor;
    private volatile boolean isRunning = false;

    @Override
    public void start(Stage primaryStage) {
        System.out.println("Application starting...");

        // Initialize Python executor
        pythonExecutor = new PythonExecutor();

        // Create main layout
        BorderPane mainLayout = new BorderPane();
        mainLayout.setStyle("-fx-background-color: #f0f2f5;");

        // Create sections
        mainLayout.setTop(createHeader());
        mainLayout.setLeft(createInputPanel());
        mainLayout.setCenter(createCenterPanel());
        mainLayout.setBottom(createOutputPanel());

        // Create scene
        Scene scene = new Scene(mainLayout, 1200, 800);

        // Configure stage
        primaryStage.setTitle("Options Analysis Tool");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);
        primaryStage.show();

        // Check dependencies
        checkDependenciesAsync();

        System.out.println("Application started successfully!");
    }

    private VBox createHeader() {
        VBox header = new VBox(5);
        header.setAlignment(Pos.CENTER);
        header.setPadding(new Insets(15));
        header.setStyle("-fx-background-color: linear-gradient(to right, #2c3e50, #3498db);");

        Label title = new Label("Options Pricing Analysis");
        title.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label subtitle = new Label("Black-Scholes Model with Greeks Calculation");
        subtitle.setStyle("-fx-font-size: 14px; -fx-text-fill: #ecf0f1;");

        header.getChildren().addAll(title, subtitle);
        return header;
    }

    private VBox createInputPanel() {
        VBox inputPanel = new VBox(12);
        inputPanel.setPadding(new Insets(20));
        inputPanel.setPrefWidth(280);
        inputPanel.setStyle("-fx-background-color: white; -fx-background-radius: 8;");

        // Section title
        Label sectionTitle = new Label("Input Parameters");
        sectionTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        // Stock Symbol
        Label stockLabel = new Label("Stock Symbol:");
        stockLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #555;");
        stockSymbolField = new TextField("AAPL");
        stockSymbolField.setPromptText("e.g., AAPL, MSFT, GOOGL");
        stockSymbolField.setStyle("-fx-padding: 10px;");

        // Auto-uppercase
        stockSymbolField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.equals(newVal.toUpperCase())) {
                stockSymbolField.setText(newVal.toUpperCase());
            }
        });

        // Expiration Date
        Label expLabel = new Label("Expiration Date:");
        expLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #555;");
        expirationDatePicker = new DatePicker(LocalDate.now().plusMonths(1));
        expirationDatePicker.setMaxWidth(Double.MAX_VALUE);

        // Disable past dates
        expirationDatePicker.setDayCellFactory(picker -> new DateCell() {
            @Override
            public void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);
                setDisable(empty || date.isBefore(LocalDate.now().plusDays(1)));
            }
        });

        // Option Type
        Label optionLabel = new Label("Option Type:");
        optionLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #555;");
        optionTypeCombo = new ComboBox<>();
        optionTypeCombo.getItems().addAll("Call", "Put");
        optionTypeCombo.setValue("Call");
        optionTypeCombo.setMaxWidth(Double.MAX_VALUE);

        // Risk-Free Rate
        Label rateLabel = new Label("Risk-Free Interest Rate (%):");
        rateLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #555;");
        riskFreeRateField = new TextField("5.0");
        riskFreeRateField.setPromptText("e.g., 5.0");
        riskFreeRateField.setStyle("-fx-padding: 10px;");

        // Numeric validation
        riskFreeRateField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isEmpty() && !newVal.matches("\\d*\\.?\\d*")) {
                riskFreeRateField.setText(oldVal);
            }
        });

        // Volatility
        Label volLabel = new Label("Historical Volatility (%):");
        volLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #555;");
        volatilityField = new TextField("25.0");
        volatilityField.setPromptText("e.g., 25.0");
        volatilityField.setStyle("-fx-padding: 10px;");

        // Numeric validation
        volatilityField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isEmpty() && !newVal.matches("\\d*\\.?\\d*")) {
                volatilityField.setText(oldVal);
            }
        });

        // Execute Button
        executeButton = new Button("Execute Analysis");
        executeButton.setMaxWidth(Double.MAX_VALUE);
        executeButton.setStyle(
                "-fx-background-color: linear-gradient(to bottom, #27ae60, #219a52);" +
                        "-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold;" +
                        "-fx-padding: 12 20; -fx-background-radius: 5; -fx-cursor: hand;"
        );
        executeButton.setOnAction(e -> handleExecuteAction());

        // Clear and Install buttons
        clearButton = new Button("Clear");
        clearButton.setMaxWidth(Double.MAX_VALUE);
        clearButton.setStyle(
                "-fx-background-color: linear-gradient(to bottom, #ecf0f1, #bdc3c7);" +
                        "-fx-text-fill: #2c3e50; -fx-font-size: 12px; -fx-padding: 8 15;" +
                        "-fx-background-radius: 5; -fx-cursor: hand;"
        );
        clearButton.setOnAction(e -> handleClearAction());

        installDepsButton = new Button("Install Deps");
        installDepsButton.setMaxWidth(Double.MAX_VALUE);
        installDepsButton.setStyle(
                "-fx-background-color: linear-gradient(to bottom, #ecf0f1, #bdc3c7);" +
                        "-fx-text-fill: #2c3e50; -fx-font-size: 12px; -fx-padding: 8 15;" +
                        "-fx-background-radius: 5; -fx-cursor: hand;"
        );
        installDepsButton.setOnAction(e -> handleInstallDependencies());

        HBox buttonRow = new HBox(10, clearButton, installDepsButton);
        buttonRow.setAlignment(Pos.CENTER);
        HBox.setHgrow(clearButton, Priority.ALWAYS);
        HBox.setHgrow(installDepsButton, Priority.ALWAYS);

        // Progress indicator
        progressIndicator = new ProgressIndicator();
        progressIndicator.setPrefSize(40, 40);
        progressIndicator.setVisible(false);

        // Status label
        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");

        VBox progressBox = new VBox(8, progressIndicator, statusLabel);
        progressBox.setAlignment(Pos.CENTER);

        // Help box
        VBox helpBox = new VBox(3);
        helpBox.setStyle("-fx-background-color: #e8f4f8; -fx-background-radius: 5; -fx-padding: 10;");
        Label helpTitle = new Label("Quick Tips:");
        helpTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #004085;");
        Label helpText1 = new Label("• Enter valid stock symbols (e.g., AAPL)");
        Label helpText2 = new Label("• Volatility is typically 15-50%");
        Label helpText3 = new Label("• Risk-free rate ≈ Treasury yield");
        helpText1.setStyle("-fx-font-size: 10px; -fx-text-fill: #004085;");
        helpText2.setStyle("-fx-font-size: 10px; -fx-text-fill: #004085;");
        helpText3.setStyle("-fx-font-size: 10px; -fx-text-fill: #004085;");
        helpBox.getChildren().addAll(helpTitle, helpText1, helpText2, helpText3);

        // Spacer
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        // Add all to panel
        inputPanel.getChildren().addAll(
                sectionTitle,
                new Separator(),
                stockLabel, stockSymbolField,
                expLabel, expirationDatePicker,
                optionLabel, optionTypeCombo,
                rateLabel, riskFreeRateField,
                volLabel, volatilityField,
                new Separator(),
                executeButton,
                buttonRow,
                progressBox,
                spacer,
                helpBox
        );

        return inputPanel;
    }

    private VBox createCenterPanel() {
        VBox centerPanel = new VBox(10);
        centerPanel.setPadding(new Insets(20));
        centerPanel.setStyle("-fx-background-color: white; -fx-background-radius: 8;");

        Label title = new Label("Analysis Results");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        // Chart container
        StackPane chartContainer = new StackPane();
        chartContainer.setStyle("-fx-background-color: #fafafa; -fx-border-color: #e0e0e0; -fx-border-radius: 5;");
        chartContainer.setMinHeight(400);
        VBox.setVgrow(chartContainer, Priority.ALWAYS);

        placeholderLabel = new Label("Chart will appear here after analysis");
        placeholderLabel.setStyle("-fx-text-fill: #999; -fx-font-size: 16px; -fx-font-style: italic;");

        chartImageView = new ImageView();
        chartImageView.setPreserveRatio(true);
        chartImageView.setFitWidth(700);
        chartImageView.setFitHeight(500);

        chartContainer.getChildren().addAll(placeholderLabel, chartImageView);

        centerPanel.getChildren().addAll(title, chartContainer);
        return centerPanel;
    }

    private VBox createOutputPanel() {
        VBox outputPanel = new VBox(5);
        outputPanel.setPadding(new Insets(10, 20, 15, 20));
        outputPanel.setStyle("-fx-background-color: white;");

        Label title = new Label("Output Log");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");

        outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setWrapText(true);
        outputArea.setPrefHeight(140);
        outputArea.setStyle(
                "-fx-control-inner-background: #1e1e1e; -fx-text-fill: #00ff00;" +
                        "-fx-font-family: 'Consolas', 'Monaco', monospace; -fx-font-size: 12px;"
        );

        outputPanel.getChildren().addAll(title, outputArea);
        return outputPanel;
    }

    private void handleExecuteAction() {
        if (isRunning) {
            logMessage("Analysis already in progress. Please wait...");
            return;
        }

        if (!validateInputs()) {
            return;
        }

        String stockSymbol = stockSymbolField.getText().trim().toUpperCase();
        String expirationDate = expirationDatePicker.getValue().toString();
        String optionType = optionTypeCombo.getValue().toLowerCase();
        double riskFreeRate = Double.parseDouble(riskFreeRateField.getText().trim());
        double volatility = Double.parseDouble(volatilityField.getText().trim());

        logMessage("═".repeat(50));
        logMessage("Starting analysis with parameters:");
        logMessage("  Stock Symbol: " + stockSymbol);
        logMessage("  Expiration Date: " + expirationDate);
        logMessage("  Option Type: " + optionType.toUpperCase());
        logMessage("  Risk-Free Rate: " + riskFreeRate + "%");
        logMessage("  Volatility: " + volatility + "%");
        logMessage("═".repeat(50));

        setControlsEnabled(false);
        progressIndicator.setVisible(true);
        updateStatus("Running analysis...", "#007bff");

        chartImageView.setImage(null);
        placeholderLabel.setVisible(true);
        placeholderLabel.setText("Processing...");

        Task<String> analysisTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                return pythonExecutor.executePythonScript(
                        stockSymbol, expirationDate, optionType, riskFreeRate, volatility
                );
            }
        };

        analysisTask.setOnSucceeded(e -> {
            Platform.runLater(() -> {
                isRunning = false;
                logMessage(analysisTask.getValue());
                loadChartImage();
                setControlsEnabled(true);
                progressIndicator.setVisible(false);
                updateStatus("Analysis complete!", "#28a745");
            });
        });

        analysisTask.setOnFailed(e -> {
            Platform.runLater(() -> {
                isRunning = false;
                Throwable ex = analysisTask.getException();
                logMessage("ERROR: " + (ex != null ? ex.getMessage() : "Unknown error"));
                setControlsEnabled(true);
                progressIndicator.setVisible(false);
                updateStatus("Error occurred", "#dc3545");
                placeholderLabel.setText("Error: Check output log");

                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Analysis Failed");
                alert.setContentText(ex != null ? ex.getMessage() : "Unknown error");
                alert.showAndWait();
            });
        });

        isRunning = true;
        Thread thread = new Thread(analysisTask);
        thread.setDaemon(true);
        thread.start();
    }

    private void handleClearAction() {
        stockSymbolField.setText("AAPL");
        expirationDatePicker.setValue(LocalDate.now().plusMonths(1));
        optionTypeCombo.setValue("Call");
        riskFreeRateField.setText("5.0");
        volatilityField.setText("25.0");
        outputArea.clear();
        chartImageView.setImage(null);
        placeholderLabel.setVisible(true);
        placeholderLabel.setText("Chart will appear here after analysis");
        updateStatus("Ready", "#666");
        logMessage("Form cleared. Ready for new analysis.");
    }

    private void handleInstallDependencies() {
        setControlsEnabled(false);
        progressIndicator.setVisible(true);
        updateStatus("Installing dependencies...", "#007bff");
        logMessage("Installing Python dependencies...");

        Task<String> installTask = new Task<>() {
            @Override
            protected String call() throws Exception {
                return pythonExecutor.installDependencies();
            }
        };

        installTask.setOnSucceeded(e -> Platform.runLater(() -> {
            logMessage(installTask.getValue());
            setControlsEnabled(true);
            progressIndicator.setVisible(false);
            updateStatus("Dependencies installed", "#28a745");
        }));

        installTask.setOnFailed(e -> Platform.runLater(() -> {
            logMessage("Failed: " + installTask.getException().getMessage());
            setControlsEnabled(true);
            progressIndicator.setVisible(false);
            updateStatus("Installation failed", "#dc3545");
        }));

        Thread thread = new Thread(installTask);
        thread.setDaemon(true);
        thread.start();
    }

    private void checkDependenciesAsync() {
        Task<Boolean> checkTask = new Task<>() {
            @Override
            protected Boolean call() {
                return pythonExecutor.checkPythonDependencies();
            }
        };

        checkTask.setOnSucceeded(e -> Platform.runLater(() -> {
            if (checkTask.getValue()) {
                logMessage("✓ All Python dependencies are installed.");
                logMessage("✓ Python command: " + pythonExecutor.getPythonCommand());
            } else {
                logMessage("⚠ Some Python dependencies are missing.");
                logMessage("  Click 'Install Deps' or run: pip install numpy yfinance matplotlib scipy");
                updateStatus("Missing dependencies", "#ffc107");
            }
        }));

        Thread thread = new Thread(checkTask);
        thread.setDaemon(true);
        thread.start();
    }

    private boolean validateInputs() {
        StringBuilder errors = new StringBuilder();

        String symbol = stockSymbolField.getText().trim();
        if (symbol.isEmpty()) {
            errors.append("• Stock symbol is required.\n");
        } else if (!symbol.matches("^[A-Za-z]{1,5}$")) {
            errors.append("• Stock symbol must be 1-5 letters.\n");
        }

        LocalDate expDate = expirationDatePicker.getValue();
        if (expDate == null) {
            errors.append("• Expiration date is required.\n");
        } else if (!expDate.isAfter(LocalDate.now())) {
            errors.append("• Expiration date must be in the future.\n");
        }

        try {
            double rate = Double.parseDouble(riskFreeRateField.getText().trim());
            if (rate < 0 || rate > 100) {
                errors.append("• Risk-free rate must be between 0 and 100.\n");
            }
        } catch (NumberFormatException e) {
            errors.append("• Risk-free rate must be a valid number.\n");
        }

        try {
            double vol = Double.parseDouble(volatilityField.getText().trim());
            if (vol <= 0 || vol > 500) {
                errors.append("• Volatility must be between 0 and 500.\n");
            }
        } catch (NumberFormatException e) {
            errors.append("• Volatility must be a valid number.\n");
        }

        if (errors.length() > 0) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Validation Error");
            alert.setHeaderText("Please correct the following errors:");
            alert.setContentText(errors.toString());
            alert.showAndWait();
            return false;
        }

        return true;
    }

    private void loadChartImage() {
        try {
            String tempDir = System.getProperty("java.io.tmpdir");
            File chartFile = new File(tempDir, "options_chart.png");

            if (chartFile.exists()) {
                Image chartImage;
                try (java.io.FileInputStream imageStream = new java.io.FileInputStream(chartFile)) {
                    chartImage = new Image(imageStream);
                }

                if (!chartImage.isError()) {
                    chartImageView.setImage(chartImage);
                    placeholderLabel.setVisible(false);
                    logMessage("Chart loaded successfully.");
                } else {
                    logMessage("Failed to load chart image: " + chartImage.getException());
                }
            } else {
                logMessage("Chart file not found at: " + chartFile.getAbsolutePath());
            }
        } catch (Exception e) {
            logMessage("Error loading chart: " + e.getMessage());
        }
    }

    private void setControlsEnabled(boolean enabled) {
        stockSymbolField.setDisable(!enabled);
        expirationDatePicker.setDisable(!enabled);
        optionTypeCombo.setDisable(!enabled);
        riskFreeRateField.setDisable(!enabled);
        volatilityField.setDisable(!enabled);
        executeButton.setDisable(!enabled);
        clearButton.setDisable(!enabled);
        installDepsButton.setDisable(!enabled);
    }

    private void logMessage(String message) {
        Platform.runLater(() -> {
            String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            outputArea.appendText("[" + timestamp + "] " + message + "\n");
            outputArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    private void updateStatus(String message, String color) {
        Platform.runLater(() -> {
            statusLabel.setText(message);
            statusLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}
