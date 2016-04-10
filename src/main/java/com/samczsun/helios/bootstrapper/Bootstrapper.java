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

package com.samczsun.helios.bootstrapper;

import com.eclipsesource.json.Json;
import com.eclipsesource.json.JsonObject;
import net.dongliu.vcdiff.VcdiffDecoder;

import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
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
    private static final String SWT_VERSION = "4.5.2";
    private static final File DATA_DIR = new File(System.getProperty("user.home") + File.separator + ".helios");
    private static final long MEGABYTE = 1024L * 1024L;
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.00");
    private static final String LATEST_JAR = "https://ci.samczsun.com/job/Helios/lastSuccessfulBuild/artifact/target/helios-0.0.1.jar";

    static final File BOOTSTRAPPER_FILE;
    private static final File IMPL_FILE;

    static {
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
        File file = new File(url.getFile());
        if (file.isDirectory()) {
            if (!Boolean.getBoolean("com.heliosdecompiler.isDebugging")) {
                JOptionPane.showMessageDialog(null, "Error: Could not locate Bootstrapper. (File is directory)");
                throw new RuntimeException(file.getAbsolutePath());
            } else {
                System.out.println("Warning: Could not locate bootstrapper but com.heliosdecompiler.isDebugging was set to true");
            }
        } else if (!file.exists() || !file.canRead() || !file.canWrite()) {
            JOptionPane.showMessageDialog(null, "Error: Could not locate Bootstrapper. (File does not exist)");
            throw new RuntimeException();
        }
        BOOTSTRAPPER_FILE = file;
        System.getProperties().put("com.heliosdecompiler.bootstrapperFile", BOOTSTRAPPER_FILE);

        if (!DATA_DIR.exists() && !DATA_DIR.mkdirs()) {
            JOptionPane.showMessageDialog(null, "Error: Could not create data directory (" + DATA_DIR.getAbsolutePath() + ")");
            throw new RuntimeException();
        }

        IMPL_FILE = new File(DATA_DIR, "helios.jar");
    }

    public static void main(String[] args) {
        try {
            if (args.length == 1) {
                if (args[0].equals("-forceupdate")) {
                    File backupFile = new File(DATA_DIR, "helios.jar.bak");
                    if (!IMPL_FILE.exists()) {
                        throw new RuntimeException("No Helios implementation found");
                    }
                    if (IMPL_FILE.isDirectory()) {
                        throw new RuntimeException("Helios implementation is directory");
                    }
                    if (!IMPL_FILE.canRead()) {
                        throw new RuntimeException("Read permissions denied for Helios implementation");
                    }
                    if (!IMPL_FILE.canWrite()) {
                        throw new RuntimeException("Write permissions denied for Helios implementation");
                    }
                    if (backupFile.exists()) {
                        if (!backupFile.canRead()) {
                            throw new RuntimeException("Read permissions denied for backup file");
                        }
                        if (!backupFile.canWrite()) {
                            throw new RuntimeException("Write permissions denied for backup file");
                        }
                        if (!backupFile.delete()) {
                            throw new RuntimeException("Could not delete backup file");
                        }
                    }
                    try {
                        Files.copy(IMPL_FILE.toPath(), backupFile.toPath());
                    } catch (IOException exception) {
                        // We're going to wrap it so end users know what went wrong
                        throw new IOException("Could not back up Helios implementation", exception);
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
                            int buildNumber = loadHelios();
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
                                            URL url = new URL("https://ci.samczsun.com/job/Helios/" + buildNumber + "/artifact/target/delta.patch");
                                            con = (HttpURLConnection) url.openConnection();
                                            if (con.getResponseCode() == 200) {
                                                File dest = new File(DATA_DIR, "delta.patch");
                                                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                                                copy(con.getInputStream(), byteArrayOutputStream);
                                                FileOutputStream fileOutputStream = new FileOutputStream(dest);
                                                fileOutputStream.write(byteArrayOutputStream.toByteArray());
                                                fileOutputStream.close();
                                                File cur = new File(DATA_DIR, "helios.jar");
                                                File old = new File(DATA_DIR, "helios-" + oldBuildNumber + ".jar");
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
                                    }
                                }
                            } else {
                                break;
                            }
                        }

                        if (!aborted) {
                            int buildNumber = loadHelios();
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
            } else {
                String[] newArgs;
                if (args.length == 0) {
                    newArgs = new String[0];
                } else {
                    if (args[0].equals("-open")) {
                        newArgs = new String[args.length - 1];
                        System.arraycopy(args, 1, newArgs, 0, newArgs.length);
                    } else {
                        newArgs = args;
                    }
                }
                byte[] data = loadSWTLibrary();
                System.out.println("Loaded SWT Library");
                int buildNumber = loadHelios();
                System.out.println("Running Helios version " + buildNumber);
                System.setProperty("com.heliosdecompiler.buildNumber", String.valueOf(buildNumber));

                new Thread(new UpdaterTask(buildNumber)).start();

                URL.setURLStreamHandlerFactory(protocol -> { //JarInJar!
                    if (protocol.equals("swt")) {
                        return new URLStreamHandler() {
                            protected URLConnection openConnection(URL u) {
                                return new URLConnection(u) {
                                    public void connect() {
                                    }

                                    public InputStream getInputStream() {
                                        return new ByteArrayInputStream(data);
                                    }
                                };
                            }

                            protected void parseURL(URL u, String spec, int start, int limit) {
                                // Don't parse or it's too slow
                            }
                        };
                    }
                    return null;
                });

                ClassLoader classLoader = new URLClassLoader(new URL[]{new URL("swt://load"), new File(DATA_DIR, "helios.jar").toURI().toURL()});
                Class<?> bootloader = Class.forName("com.samczsun.helios.bootloader.Bootloader", false, classLoader);
                bootloader.getMethod("main", String[].class).invoke(null, new Object[]{newArgs});
            }
        } catch (Throwable t) {
            displayError(t);
            System.exit(1);
        }
    }

    private static int loadHelios() throws IOException {
        System.out.println("Finding Helios implementation");

        int buildNumber = -1;

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
                        buildNumber = Integer.parseInt(ver);
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
                            centreWindow(frame);
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
            } else {
                throw new IOException(connection.getResponseCode() + ": " + connection.getResponseMessage());
            }
        }
        return buildNumber;
    }

    private static byte[] loadSWTLibrary() throws IOException, ReflectiveOperationException {
        String name = getOSName();
        if (name == null) throw new IllegalArgumentException("Cannot determine OS");
        String arch = getArch();
        if (arch == null) throw new IllegalArgumentException("Cannot determine architecture");

        String artifactId = "org.eclipse.swt." + name + "." + arch;
        String swtLocation = artifactId + "-" + SWT_VERSION + ".jar";

        System.out.println("Loading SWT version " + swtLocation);

        byte[] data = null;

        File savedJar = new File(DATA_DIR, swtLocation);
        if (savedJar.isDirectory() && !savedJar.delete())
            throw new IllegalArgumentException("Saved file is a directory and could not be deleted");

        if (savedJar.exists() && savedJar.canRead()) {
            try {
                System.out.println("Loading from saved file");
                InputStream inputStream = new FileInputStream(savedJar);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                copy(inputStream, outputStream);
                data = outputStream.toByteArray();
            } catch (IOException exception) {
                System.out.println("Failed to load from saved file.");
                exception.printStackTrace(System.out);
            }
        }
        if (data == null) {
            InputStream fromJar = Bootstrapper.class.getResourceAsStream("/swt/" + swtLocation);
            if (fromJar != null) {
                try {
                    System.out.println("Loading from within JAR");
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    copy(fromJar, outputStream);
                    data = outputStream.toByteArray();
                } catch (IOException exception) {
                    System.out.println("Failed to load within JAR");
                    exception.printStackTrace(System.out);
                }
            }
        }
        if (data == null) {
            URL url = new URL("https://maven-eclipse.github.io/maven/org/eclipse/swt/" + artifactId + "/" + SWT_VERSION + "/" + swtLocation);
            try {
                System.out.println("Loading over the internet");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                if (connection.getResponseCode() == 200) {
                    InputStream fromURL = connection.getInputStream();
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    copy(fromURL, outputStream);
                    data = outputStream.toByteArray();
                } else {
                    throw new IOException(connection.getResponseCode() + ": " + connection.getResponseMessage());
                }
            } catch (IOException exception) {
                System.out.println("Failed to load over the internet");
                exception.printStackTrace(System.out);
            }
        }

        if (data == null) {
            throw new IllegalArgumentException("Failed to load SWT");
        }

        if (!savedJar.exists()) {
            try {
                System.out.println("Writing to saved file");
                if (savedJar.createNewFile()) {
                    FileOutputStream fileOutputStream = new FileOutputStream(savedJar);
                    fileOutputStream.write(data);
                    fileOutputStream.close();
                } else {
                    throw new IOException("Could not create new file");
                }
            } catch (IOException exception) {
                JOptionPane.showMessageDialog(null, "Failed to save SWT to cache. If this persists please open a GitHub issue");
                System.out.println("Failed to write to saved file");
                exception.printStackTrace(System.out);
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

    private static String getArch() {
        String arch = System.getProperty("sun.arch.data.model");
        if (arch == null) arch = System.getProperty("com.ibm.vm.bitmode");
        return "32".equals(arch) ? "x86" : "64".equals(arch) ? "x86_64" : null;
    }

    private static String getOSName() {
        String unparsedName = System.getProperty("os.name").toLowerCase();
        if (unparsedName.contains("win")) return "win32.win32";
        if (unparsedName.contains("mac")) return "cocoa.macosx";
        if (unparsedName.contains("linux")) return "gtk.linux";
        return null;
    }

    static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[4096];
        int n;
        while ((n = input.read(buffer)) != -1) {
            output.write(buffer, 0, n);
        }
    }

    private static String bytesToMeg(double bytes) {
        return DECIMAL_FORMAT.format(bytes / MEGABYTE);
    }

    private static void centreWindow(Window frame) {
        Dimension dimension = Toolkit.getDefaultToolkit().getScreenSize();
        int x = (int) ((dimension.getWidth() - frame.getWidth()) / 2);
        int y = (int) ((dimension.getHeight() - frame.getHeight()) / 2);
        frame.setLocation(x, y);
    }
}
