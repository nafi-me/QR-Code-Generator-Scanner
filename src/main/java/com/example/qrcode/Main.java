package com.example.qrcode;

import com.github.sarxos.webcam.Webcam;
import com.google.zxing.*;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.EnumMap;
import java.util.Map;

public class Main extends Application {

    // UI nodes we'll need across methods
    private ImageView qrPreview;
    private TextArea qrInput;
    private TextField sizeField;
    private Label statusLabel;
    private ImageView cameraView;
    private Button startCamBtn;
    private volatile boolean scanningFromCam = false;
    private Webcam webcam;
    private ExecutorService camExecutor;

    @Override
    public void start(Stage stage) {
        stage.setTitle("QR Code Generator & Scanner");

        // Left: generator
        VBox left = new VBox(10);
        left.setPadding(new Insets(14));
        left.setPrefWidth(380);
        left.setStyle("-fx-background-color: linear-gradient(#0b1220, #071124); -fx-border-radius:10; -fx-background-radius:10;");

        Label genTitle = new Label("QR Code Generator");
        genTitle.setStyle("-fx-font-size:18px; -fx-text-fill: white;");

        qrInput = new TextArea();
        qrInput.setPromptText("Enter text, URL, or any text to encode as QR code...");
        qrInput.setWrapText(true);
        qrInput.setPrefRowCount(6);

        HBox sizeRow = new HBox(8);
        sizeRow.setAlignment(Pos.CENTER_LEFT);
        sizeField = new TextField("300");
        sizeField.setPrefWidth(90);
        Label px = new Label("px");
        px.setStyle("-fx-text-fill: #cbd5e1;");
        sizeRow.getChildren().addAll(new Label("Size:"), sizeField, px);

        Button genBtn = new Button("Generate");
        Button saveBtn = new Button("Save PNG");
        Button loadBtn = new Button("Load image to scan");
        genBtn.setDefaultButton(true);

        HBox genActions = new HBox(8, genBtn, saveBtn);
        genActions.setAlignment(Pos.CENTER_LEFT);

        qrPreview = new ImageView();
        qrPreview.setFitWidth(320);
        qrPreview.setFitHeight(320);
        qrPreview.setPreserveRatio(true);
        qrPreview.setSmooth(true);
        qrPreview.setStyle("-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 12, 0, 0, 6);");

        left.getChildren().addAll(genTitle, qrInput, sizeRow, genActions, saveBtn, new Separator(), new Label("Preview:"), qrPreview, new Separator(), loadBtn);

        // Right: scanner
        VBox right = new VBox(10);
        right.setPadding(new Insets(14));
        right.setStyle("-fx-background-color: linear-gradient(#071122, #041019); -fx-border-radius:10; -fx-background-radius:10;");
        Label scanTitle = new Label("QR Code Scanner");
        scanTitle.setStyle("-fx-font-size:18px; -fx-text-fill: white;");

        // camera view
        cameraView = new ImageView();
        cameraView.setFitWidth(420);
        cameraView.setFitHeight(280);
        cameraView.setPreserveRatio(true);
        cameraView.setSmooth(true);
        cameraView.setStyle("-fx-background-color: #000;");

        startCamBtn = new Button("Start Camera");
        Button stopCamBtn = new Button("Stop Camera");
        stopCamBtn.setDisable(true);

        Button pickImageBtn = new Button("Open image file...");
        Label decodedLabel = new Label("Decoded content:");
        TextArea decodedArea = new TextArea();
        decodedArea.setEditable(false);
        decodedArea.setWrapText(true);
        decodedArea.setPrefRowCount(5);

        statusLabel = new Label("Ready");
        statusLabel.setStyle("-fx-text-fill: #cbd5e1;");

        HBox camActions = new HBox(8, startCamBtn, stopCamBtn, pickImageBtn);
        camActions.setAlignment(Pos.CENTER_LEFT);

        right.getChildren().addAll(scanTitle, cameraView, camActions, statusLabel, new Separator(), decodedLabel, decodedArea);

        // Layout top-level
        HBox root = new HBox(12, left, right);
        root.setPadding(new Insets(12));
        root.setStyle("-fx-background-color: linear-gradient(#071324, #00101a);");

        Scene scene = new Scene(root, 830, 620);
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        // ---------- Handlers ----------

        // Generate button
        genBtn.setOnAction(e -> {
            String text = qrInput.getText();
            if (text == null || text.isBlank()) {
                status("Enter text to encode first.", true);
                return;
            }
            int size = clampParseSize(sizeField.getText(), 80, 2000, 300);
            try {
                BufferedImage img = generateQRCodeImage(text, size, size);
                Image fxImage = SwingFXUtils.toFXImage(img, null);
                qrPreview.setImage(fxImage);
                status("QR code generated (" + size + "x" + size + ").", false);
            } catch (WriterException ex) {
                status("Failed to generate QR: " + ex.getMessage(), true);
            }
        });

        // Save PNG
        saveBtn.setOnAction(e -> {
            if (qrPreview.getImage() == null) {
                status("Generate a QR code first.", true);
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save QR as PNG");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG Image", "*.png"));
            chooser.setInitialFileName("qrcode.png");
            File out = chooser.showSaveDialog(stage);
            if (out != null) {
                try {
                    BufferedImage b = SwingFXUtils.fromFXImage(qrPreview.getImage(), null);
                    ImageIO.write(b, "PNG", out);
                    status("Saved: " + out.getName(), false);
                } catch (IOException ex) {
                    status("Save failed: " + ex.getMessage(), true);
                }
            }
        });

        // Load image to scan (file)
        pickImageBtn.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Open image to scan");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg", "*.bmp")
            );
            File f = chooser.showOpenDialog(stage);
            if (f != null) {
                try {
                    BufferedImage buffered = ImageIO.read(f);
                    String decoded = decodeQRCode(buffered);
                    if (decoded == null) {
                        decodedArea.setText("");
                        status("No QR code found in image.", true);
                    } else {
                        decodedArea.setText(decoded);
                        status("Decoded from image.", false);
                    }
                } catch (IOException ex) {
                    status("Failed to read image: " + ex.getMessage(), true);
                }
            }
        });

        // Start/Stop camera
        startCamBtn.setOnAction(e -> {
            startCamBtn.setDisable(true);
            scanningFromCam = true;
            camExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cam-worker");
                t.setDaemon(true);
                return t;
            });
            camExecutor.submit(() -> {
                try {
                    // open default webcam
                    webcam = Webcam.getDefault();
                    if (webcam == null) {
                        Platform.runLater(() -> {
                            status("No webcam detected.", true);
                            startCamBtn.setDisable(false);
                        });
                        return;
                    }
                    webcam.open(true);
                    Platform.runLater(() -> {
                        status("Camera started.", false);
                        startCamBtn.setDisable(true);
                        stopCamBtn.setDisable(false);
                    });

                    // repeatedly capture frames and try to decode
                    while (scanningFromCam && webcam.isOpen()) {
                        BufferedImage frame = webcam.getImage();
                        if (frame != null) {
                            Image fx = SwingFXUtils.toFXImage(frame, null);
                            Platform.runLater(() -> cameraView.setImage(fx));
                            // try decode quickly
                            String decoded = null;
                            try {
                                decoded = decodeQRCode(frame);
                            } catch (Exception ex) {
                                // ignore decode errors
                            }
                            if (decoded != null && !decoded.isBlank()) {
                                final String out = decoded;
                                Platform.runLater(() -> {
                                    decodedArea.setText(out);
                                    status("Decoded from camera.", false);
                                });
                                // you can break or continue; we continue but sleep a bit
                                Thread.sleep(1200);
                            } else {
                                Thread.sleep(80);
                            }
                        } else {
                            Thread.sleep(100);
                        }
                    }
                } catch (Exception ex) {
                    Platform.runLater(() -> status("Camera error: " + ex.getMessage(), true));
                } finally {
                    if (webcam != null && webcam.isOpen()) {
                        try { webcam.close(); } catch (Exception ignored) {}
                    }
                    Platform.runLater(() -> {
                        startCamBtn.setDisable(false);
                        stopCamBtn.setDisable(true);
                        scanningFromCam = false;
                    });
                }
            });
        });

        stopCamBtn.setOnAction(e -> {
            scanningFromCam = false;
            if (camExecutor != null) {
                camExecutor.shutdownNow();
            }
            if (webcam != null && webcam.isOpen()) {
                try { webcam.close(); } catch (Exception ignored) {}
            }
            startCamBtn.setDisable(false);
            stopCamBtn.setDisable(true);
            status("Camera stopped.", false);
        });

        // Generate on Enter (CTRL+Enter in text area)
        qrInput.setOnKeyPressed(ev -> {
            if (ev.getCode() == KeyCode.ENTER && ev.isControlDown()) {
                genBtn.fire();
            }
        });

        // initial sample content
        qrInput.setText("https://example.com");
    }

    @Override
    public void stop() throws Exception {
        // cleanup camera thread
        scanningFromCam = false;
        if (camExecutor != null) {
            camExecutor.shutdownNow();
        }
        if (webcam != null && webcam.isOpen()) {
            try { webcam.close(); } catch (Exception ignored) {}
        }
        super.stop();
    }

    private void status(String text, boolean isError) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            statusLabel.setTextFill(isError ? Color.web("#ff6b6b") : Color.web("#9be7ff"));
        });
    }

    // ---------- ZXing helpers ----------

    private BufferedImage generateQRCodeImage(String text, int width, int height) throws WriterException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        BitMatrix bitMatrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height, hints);
        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }

    private String decodeQRCode(BufferedImage image) {
        if (image == null) return null;
        LuminanceSource source = new BufferedImageLuminanceSource(image);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        try {
            Result result = new MultiFormatReader().decode(bitmap);
            return result.getText();
        } catch (NotFoundException e) {
            // no QR code found
            return null;
        } catch (FormatException | ChecksumException e) {
            return null;
        }
    }

    private int clampParseSize(String text, int min, int max, int fallback) {
        try {
            int v = Integer.parseInt(text.trim());
            if (v < min) return min;
            if (v > max) return max;
            return v;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static void main(String[] args) {
        launch();
    }
}
