/*
 * Copyright 2016 Sam Sun <me@samczsun.com>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.heliosdecompiler.bootstrapper;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;
import net.dongliu.vcdiff.VcdiffDecoder;
import net.dongliu.vcdiff.exception.VcdiffDecodeException;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.GridLayout;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.text.DecimalFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

public class Bootstrapper {
    private static final String IMPLEMENTATION_VERSION = "0.0.6";

    private static final File DATA_DIR = new File(System.getProperty("user.home") + File.separator + ".helios");
    private static final long MEGABYTE = 1024L * 1024L;
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.00");

    private static final String LATEST_JAR = String.format("https://ci.samczsun.com/job/Helios/lastSuccessfulBuild/artifact/target/helios-%s.jar", IMPLEMENTATION_VERSION);
    static File BOOTSTRAPPER_FILE;
    private static final File IMPL_FILE;

    static {
        if (!DATA_DIR.exists() && !DATA_DIR.mkdirs()) {
            JOptionPane.showMessageDialog(null, "Error: Could not create data directory (" + DATA_DIR.getAbsolutePath() + ")");
            System.exit(1);
            throw new RuntimeException();
        }

        IMPL_FILE = new File(DATA_DIR, "helios-" + IMPLEMENTATION_VERSION + ".jar");

        BOOTSTRAPPER_FILE = locateBootstrapperFile();
        System.getProperties().put("com.heliosdecompiler.bootstrapperFile", BOOTSTRAPPER_FILE);

        try (InputStream inputStream = Bootstrapper.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            Manifest manifest = new Manifest(inputStream);
            String buildVersion = manifest.getMainAttributes().getValue("Implementation-Version");
            if (buildVersion != null && !buildVersion.equals(IMPLEMENTATION_VERSION)) {
                throw new RuntimeException(String.format("Implementation Versions do not match (Expected: %s, got %s)", buildVersion, IMPLEMENTATION_VERSION));
            }
        } catch (IOException ignored) {
        }
    }

    private static File locateBootstrapperFile() {
        ProtectionDomain protectionDomain = Bootstrapper.class.getProtectionDomain();
        if (protectionDomain == null) {
            JOptionPane.showMessageDialog(null, "Error: Could not locate Bootstrapper. (ProtectionDomain was null)");
            throw new RuntimeException();
        }
        CodeSource codeSource = protectionDomain.getCodeSource();
        if (codeSource == null) {
            JOptionPane.showMessageDialog(null, "Error: Could not locate Bootstrapper. (CodeSource was null)");
            throw new RuntimeException();
        }
        URL url = codeSource.getLocation();
        if (url == null) {
            JOptionPane.showMessageDialog(null, "Error: Could not locate Bootstrapper. (Location was null)");
            throw new RuntimeException();
        }
        try {
            URI uri = url.toURI();
            File file = new File(uri.getPath());
            if (file.isDirectory()) {
                if (!Boolean.getBoolean("com.heliosdecompiler.isDebugging")) {
                    JOptionPane.showMessageDialog(null, "Error: Could not locate Bootstrapper. (File is directory)");
                    throw new RuntimeException(file.getAbsolutePath());
                } else {
                    System.out.println("Warning: Could not locate bootstrapper but com.heliosdecompiler.isDebugging was set to true");
                }
            } else if (!file.exists()) {
                JOptionPane.showMessageDialog(null, "Error: Could not locate Bootstrapper. (File does not exist)");
                throw new RuntimeException();
            } else if (!file.canRead()) {
                JOptionPane.showMessageDialog(null, "Error: Could not locate Bootstrapper. (File is not readable)");
                throw new RuntimeException();
            }
            return file;
        } catch (URISyntaxException e) {
            JOptionPane.showMessageDialog(null, "Error: Could not locate Bootstrapper. (URISyntaxException)");
            throw new RuntimeException();
        }
    }

    public static void main(String[] args) {
        Options options = new Options();
        options.addOption(
                Option.builder("Xfu")
                        .longOpt("Xforceupdate")
                        .desc("Force the patching process")
                        .build()
        );
        options.addOption(
                Option.builder("Xh")
                        .longOpt("Xhelp")
                        .desc("Help!")
                        .build()
        );
        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine commandLine = parser.parse(options, args, true);
            if (commandLine.hasOption("Xhelp")) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp("java -jar bootstrapper.jar", options);
            } else if (commandLine.hasOption("Xforceupdate")) {
                forceUpdate();
            } else {
//                boolean shouldStart = true;
//                ServerSocket socket = null;
//                try {
//                    socket = new ServerSocket(21354);
//                } catch (IOException e) {
//                    shouldStart = false;
//                } finally {
//                    if (socket != null) {
//                        socket.close();
//                    }
//                }

//                if (shouldStart) {
                String[] forward = commandLine.getArgs();
                HeliosData heliosData = loadHelios();
                System.out.println("Running Helios version " + heliosData.buildNumber);

                System.getProperties().put("com.heliosdecompiler.buildNumber", String.valueOf(heliosData.buildNumber));
                System.getProperties().put("com.heliosdecompiler.version", String.valueOf(heliosData.version));
                System.getProperties().put("com.heliosdecompiler.args", args);

                new Thread(new UpdaterTask(heliosData.buildNumber)).start();

                ClassLoader classLoader = new URLClassLoader(new URL[]{IMPL_FILE.toURI().toURL()}, null);
                Class<?> bootloader = Class.forName(heliosData.mainClass, false, classLoader);
                bootloader.getMethod("main", String[].class).invoke(null, new Object[]{forward});
//                } else {
//                    String message = Arrays.asList(forward).stream().collect(Collectors.joining(" "));
//                    Socket clientSocket = new Socket("127.0.0.1", 21354);
//                    clientSocket.getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));
//                    clientSocket.getOutputStream().close();
//                    clientSocket.close();
//                }
            }
        } catch (Throwable t) {
            displayError(t);
            System.exit(1);
        }
    }

    private static void forceUpdate() throws IOException, VcdiffDecodeException {
        File backupFile = new File(DATA_DIR, "helios.jar.bak");
        try {
            Files.copy(IMPL_FILE.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            // We're going to wrap it so end users know what went wrong
            throw new IOException(String.format("Could not back up Helios implementation (%s %s, %s %s)", IMPL_FILE.canRead(), IMPL_FILE.canWrite(), backupFile.canRead(), backupFile.canWrite()), exception);
        }
        URL latestVersion = new URL("https://ci.samczsun.com/job/Helios/lastStableBuild/buildNumber");
        HttpURLConnection connection = (HttpURLConnection) latestVersion.openConnection();
        if (connection.getResponseCode() == 200) {
            boolean aborted = false;

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            copy(connection.getInputStream(), outputStream);
            String version = new String(outputStream.toByteArray(), "UTF-8");
            System.out.println("Latest version: " + version);
            int intVersion = Integer.parseInt(version);

            loop:
            while (true) {
                int buildNumber = loadHelios().buildNumber;
                int oldBuildNumber = buildNumber;
                System.out.println("Current Helios version is " + buildNumber);

                if (buildNumber < intVersion) {
                    while (buildNumber <= intVersion) {
                        buildNumber++;
                        URL status = new URL("https://ci.samczsun.com/job/Helios/" + buildNumber + "/api/json");
                        HttpURLConnection con = (HttpURLConnection) status.openConnection();
                        if (con.getResponseCode() == 200) {
                            JsonObject object = Json.parse(new InputStreamReader(con.getInputStream())).asObject();
                            if (object.get("result").asString().equals("SUCCESS")) {
                                JsonArray artifacts = object.get("artifacts").asArray();
                                for (JsonValue value : artifacts.values()) {
                                    JsonObject artifact = value.asObject();
                                    String name = artifact.get("fileName").asString();
                                    if (name.contains("helios-") && !name.contains(IMPLEMENTATION_VERSION)) {
                                        JOptionPane.showMessageDialog(null, "Bootstrapper is out of date. Patching cannot continue");
                                        aborted = true;
                                        break loop;
                                    }
                                }
                                URL url = new URL("https://ci.samczsun.com/job/Helios/" + buildNumber + "/artifact/target/delta.patch");
                                con = (HttpURLConnection) url.openConnection();
                                if (con.getResponseCode() == 200) {
                                    File dest = new File(DATA_DIR, "delta.patch");
                                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                                    copy(con.getInputStream(), byteArrayOutputStream);
                                    FileOutputStream fileOutputStream = new FileOutputStream(dest);
                                    fileOutputStream.write(byteArrayOutputStream.toByteArray());
                                    fileOutputStream.close();
                                    File cur = IMPL_FILE;
                                    File old = new File(IMPL_FILE.getAbsolutePath() + "." + oldBuildNumber);
                                    if (cur.renameTo(old)) {
                                        VcdiffDecoder.decode(old, dest, cur);
                                        old.delete();
                                        dest.delete();
                                        continue loop;
                                    } else {
                                        throw new IllegalArgumentException("Could not rename");
                                    }
                                }
                            }
                        } else {
                            JOptionPane.showMessageDialog(null, "Server returned response code " + con.getResponseCode() + " " + con.getResponseMessage() + "\nAborting patch process", null, JOptionPane.INFORMATION_MESSAGE);
                            aborted = true;
                            break loop;
                        }
                    }
                } else {
                    break;
                }
            }

            if (!aborted) {
                int buildNumber = loadHelios().buildNumber;
                System.out.println("Running Helios version " + buildNumber);
                JOptionPane.showMessageDialog(null, "Updated Helios to version " + buildNumber + "!");
                Runtime.getRuntime().exec(new String[]{
                        "java",
                        "-jar",
                        BOOTSTRAPPER_FILE.getAbsolutePath()
                });
            } else {
                try {
                    Files.copy(backupFile.toPath(), IMPL_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException exception) {
                    // We're going to wrap it so end users know what went wrong
                    throw new IOException("Critical Error! Could not restore Helios implementation to original copy" +
                            "Try relaunching the Bootstrapper. If that doesn't work open a GitHub issue with details", exception);
                }
            }
            System.exit(0);
        } else {
            throw new IOException(connection.getResponseCode() + ": " + connection.getResponseMessage());
        }
    }

    private static HeliosData loadHelios() throws IOException {
        System.out.println("Finding Helios implementation");

        HeliosData data = new HeliosData();

        boolean needsToDownload = !IMPL_FILE.exists();
        if (!needsToDownload) {
            try (JarFile jarFile = new JarFile(IMPL_FILE)) {
                ZipEntry entry = jarFile.getEntry("META-INF/MANIFEST.MF");
                if (entry == null) {
                    needsToDownload = true;
                } else {
                    Manifest manifest = new Manifest(jarFile.getInputStream(entry));
                    String ver = manifest.getMainAttributes().getValue("Implementation-Version");
                    try {
                        data.buildNumber = Integer.parseInt(ver);
                        data.version = manifest.getMainAttributes().getValue("Version");
                        data.mainClass = manifest.getMainAttributes().getValue("Main-Class");
                    } catch (NumberFormatException e) {
                        needsToDownload = true;
                    }
                }
            } catch (IOException e) {
                needsToDownload = true;
            }
        }
        if (needsToDownload) {
            URL latestJar = new URL(LATEST_JAR);
            System.out.println("Downloading latest Helios implementation");

            FileOutputStream out = new FileOutputStream(IMPL_FILE);
            HttpURLConnection connection = (HttpURLConnection) latestJar.openConnection();
            if (connection.getResponseCode() == 200) {
                int contentLength = connection.getContentLength();
                if (contentLength > 0) {
                    InputStream stream = connection.getInputStream();
                    byte[] buffer = new byte[1024];
                    int amnt;
                    AtomicInteger total = new AtomicInteger();
                    AtomicBoolean stop = new AtomicBoolean(false);

                    Thread progressBar = new Thread() {
                        public void run() {
                            JPanel panel = new JPanel();
                            panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

                            JLabel label = new JLabel();
                            label.setText("Downloading latest Helios build");
                            panel.add(label);

                            GridLayout layout = new GridLayout();
                            layout.setColumns(1);
                            layout.setRows(3);
                            panel.setLayout(layout);
                            JProgressBar pbar = new JProgressBar();
                            pbar.setMinimum(0);
                            pbar.setMaximum(100);
                            panel.add(pbar);

                            JTextArea textArea = new JTextArea(1, 3);
                            textArea.setOpaque(false);
                            textArea.setEditable(false);
                            textArea.setText("Downloaded 00.00MB/00.00MB");
                            panel.add(textArea);

                            JFrame frame = new JFrame();
                            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
                            frame.setContentPane(panel);
                            frame.pack();
                            frame.setLocationRelativeTo(null);
                            frame.setVisible(true);

                            while (!stop.get()) {
                                SwingUtilities.invokeLater(() -> pbar.setValue((int) (100.0 * total.get() / contentLength)));

                                textArea.setText("Downloaded " + bytesToMeg(total.get()) + "MB/" + bytesToMeg(contentLength) + "MB");
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException ignored) {
                                }
                            }
                            frame.dispose();
                        }
                    };
                    progressBar.start();

                    while ((amnt = stream.read(buffer)) != -1) {
                        out.write(buffer, 0, amnt);
                        total.addAndGet(amnt);
                    }
                    stop.set(true);
                    return loadHelios();
                } else {
                    throw new IOException("Content-Length set to " + connection.getContentLength());
                }
            } else if (connection.getResponseCode() == 404) { // Most likely bootstrapper is out of date
                throw new RuntimeException("Bootstrapper out of date!");
            } else {
                throw new IOException(connection.getResponseCode() + ": " + connection.getResponseMessage());
            }
        }

        return data;
    }

    static void displayError(Throwable t) {
        t.printStackTrace();
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        JOptionPane.showMessageDialog(null, writer.toString(), t.getClass().getSimpleName(),
                JOptionPane.INFORMATION_MESSAGE);
    }

    static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[4096];
        int n;
        while ((n = input.read(buffer)) != -1) {
            output.write(buffer, 0, n);
        }
    }

    static String bytesToMeg(double bytes) {
        return DECIMAL_FORMAT.format(bytes / MEGABYTE);
    }
}
