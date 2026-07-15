package sunflowerr;

import java.io.*;
import java.net.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MTPTServer {

    static AtomicInteger activeClients = new AtomicInteger(0);

    // Custom structure for keeping rich metadata about indexed items
    static class IndexedItem {
        String id;
        File file; // Represents a file or a directory (for packages)
        String name;
        String category;
        boolean isDirectory;
        long size; // -1 if it's a package directory (calculated dynamically upon zipping)
    }

    private static final ConcurrentHashMap<String, IndexedItem> fileIndex = new ConcurrentHashMap<>();

    // Protocol constants
    private static final byte CMD_SPEED_TEST  = 0x01;
    private static final byte CMD_GET_FILE    = 0x02;
    private static final byte CMD_ADMIN_AUTH  = 0x03;
    private static final byte CMD_ADMIN_CMD   = 0x04;

    private static final String ADMIN_PASSWORD = generateAdminPassword();

    private static final int MAX_CONCURRENT_DISK_READS = 32;
    private static final Semaphore diskSemaphore = new Semaphore(MAX_CONCURRENT_DISK_READS, true);

    public static void main(String[] args) throws Exception {
        setupLogging();

        int port = 5050;
        indexStorageFiles();

        ServerSocket server = new ServerSocket(port);
        server.setReuseAddress(true);

        System.out.println("\n=== MTPT Server v1.1===");
        System.out.println("Port: " + port);
        System.out.println("Active Items in Database: " + fileIndex.size());

        System.out.println("\n[SECURITY] ADMIN KEY: " + ADMIN_PASSWORD);
        System.out.println("===========================================\n");

        printNetworkDiagnostics(port);

        log("Server started successfully. Waiting for connections...");

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (true) {
                Socket socket = server.accept();
                socket.setTcpNoDelay(true);
                socket.setSendBufferSize(256 * 1024);
                socket.setReceiveBufferSize(256 * 1024);

                activeClients.incrementAndGet();
                executor.submit(() -> handle(socket));
            }
        }
    }

    private static String generateAdminPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static void setupLogging() {
        try {
            File logFile = new File("server_log.txt");
            FileOutputStream fileOut = new FileOutputStream(logFile, true);
            TeeOutputStream tee = new TeeOutputStream(System.out, fileOut);
            PrintStream dualStream = new PrintStream(tee, true, "UTF-8");
            System.setOut(dualStream);
            System.setErr(dualStream);

            System.out.println("\n\n################################################################");
            log("Initializing new log session. File: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("LOG ERROR: " + e.getMessage());
        }
    }

    private static void log(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        System.out.println("[" + timestamp + "] [INFO] " + message);
    }

    private static void logWarning(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        System.out.println("[" + timestamp + "] [WARN] " + message);
    }

    private static void logError(String message, Throwable t) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        System.err.println("[" + timestamp + "] [ERROR] " + message + (t != null ? ": " + t.getMessage() : ""));
        if (t != null) {
            t.printStackTrace(System.err);
        }
    }

    private static class TeeOutputStream extends OutputStream {
        private final OutputStream consoleStream;
        private final OutputStream fileStream;
        private final ReentrantLock lock = new ReentrantLock();

        public TeeOutputStream(OutputStream consoleStream, OutputStream fileStream) {
            this.consoleStream = consoleStream;
            this.fileStream = fileStream;
        }

        @Override
        public void write(int b) throws IOException {
            lock.lock();
            try {
                consoleStream.write(b);
                fileStream.write(b);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            lock.lock();
            try {
                consoleStream.write(b, off, len);
                fileStream.write(b, off, len);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void flush() throws IOException {
            lock.lock();
            try {
                consoleStream.flush();
                fileStream.flush();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() throws IOException {
            lock.lock();
            try {
                consoleStream.close();
                fileStream.close();
            } finally {
                lock.unlock();
            }
        }
    }

    private static void indexStorageFiles() {
        File storageDir = new File("storage");
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }

        // Очищаємо та перестворюємо тимчасову папку для ZIP
        File tempDir = new File("storage/temp");
        if (tempDir.exists()) {
            deleteDirectory(tempDir);
        }
        tempDir.mkdirs();

        System.out.println("[INDEXER] Scanning directory 'storage' for Categories, Sub-dirs & Files...");
        fileIndex.clear();

        File[] rootContents = storageDir.listFiles();
        if (rootContents != null) {
            for (File entry : rootContents) {
                // Ігноруємо тимчасову папку
                if (entry.getName().equalsIgnoreCase("temp")) continue;

                if (entry.isDirectory()) {
                    String categoryName = entry.getName();

                    // 1. Реєструємо саму кореневу папку категорії як глобальний пакет (якщо захочеться завантажити ВСЮ категорію разом)
                    String packageId = generateFileId(entry);
                    IndexedItem pkg = new IndexedItem();
                    pkg.id = packageId;
                    pkg.file = entry;
                    pkg.name = entry.getName() + ".zip";
                    pkg.category = "Package";
                    pkg.isDirectory = true;
                    pkg.size = -1;
                    fileIndex.put(packageId, pkg);
                    System.out.printf("  -> [PKG ID: %s] | %s (Category Root Package)\n", packageId, entry.getName());

                    // 2. Обходимо ВСЕ всередині папки категорії (і файли, і вкладені папки)
                    try {
                        Files.walk(entry.toPath())
                                .forEach(path -> {
                                    File file = path.toFile();
                                    // Пропускаємо саму кореневу папку категорії (вона вже зареєстрована вище)
                                    if (file.equals(entry)) return;

                                    String fileId = generateFileId(file);
                                    IndexedItem item = new IndexedItem();
                                    item.id = fileId;
                                    item.file = file;
                                    item.category = categoryName; // Усі вкладені елементи успадковують категорію (напр. "ISO")

                                    if (file.isDirectory()) {
                                        // Якщо це підпапка (наприклад, Kingston)
                                        item.name = file.getName() + ".zip";
                                        item.isDirectory = true;
                                        item.size = -1; // Розмір визначиться після архівації
                                        fileIndex.put(fileId, item);
                                        System.out.printf("     -> [SUB-DIR ID: %s] [%s] | %s/ (Sub-directory Package)\n", fileId, categoryName, file.getName());
                                    } else {
                                        // Якщо це файл всередині категорії (навіть глибоко вкладений)
                                        item.name = file.getName();
                                        item.isDirectory = false;
                                        item.size = file.length();
                                        fileIndex.put(fileId, item);
                                        System.out.printf("     -> [FILE ID: %s] [%s] | %s (%,d bytes)\n", fileId, categoryName, file.getName(), file.length());
                                    }
                                });
                    } catch (IOException e) {
                        logError("Disk scanning error inside directory " + categoryName, e);
                    }
                } else if (entry.isFile()) {
                    // Файли в самому корені storage отримують категорію General
                    String fileId = generateFileId(entry);
                    IndexedItem item = new IndexedItem();
                    item.id = fileId;
                    item.file = entry;
                    item.name = entry.getName();
                    item.category = "General";
                    item.isDirectory = false;
                    item.size = entry.length();
                    fileIndex.put(fileId, item);
                    System.out.printf("  -> [ID: %s] [General] | %s (%,d bytes)\n", fileId, entry.getName(), entry.length());
                }
            }
        }

        System.out.println("[INDEXER] Total indexed database nodes: " + fileIndex.size());
        saveIndexToDatabase();
    }

    private static String generateFileId(File file) {
        int hash = Math.abs(file.getPath().replace("\\", "/").hashCode());
        int id = 100000 + (hash % 900000);
        return String.valueOf(id);
    }

    private static void saveIndexToDatabase() {
        String dbUrl = "jdbc:sqlite:mtpt_metadata.db";
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection conn = DriverManager.getConnection(dbUrl)) {
                // Drop old table to adapt clean schema update
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP TABLE IF EXISTS indexed_files");
                }

                String createTableSQL = "CREATE TABLE IF NOT EXISTS indexed_files (" +
                        "id TEXT PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "path TEXT NOT NULL, " +
                        "size INTEGER NOT NULL, " +
                        "category TEXT NOT NULL, " +
                        "is_directory INTEGER NOT NULL, " +
                        "indexed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";

                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(createTableSQL);
                }

                conn.setAutoCommit(false);
                String insertSQL = "INSERT INTO indexed_files (id, name, path, size, category, is_directory) VALUES (?, ?, ?, ?, ?, ?)";

                try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
                    for (Map.Entry<String, IndexedItem> entry : fileIndex.entrySet()) {
                        IndexedItem item = entry.getValue();
                        pstmt.setString(1, item.id);
                        pstmt.setString(2, item.name);
                        pstmt.setString(3, item.file.getPath().replace("\\", "/"));
                        pstmt.setLong(4, item.size);
                        pstmt.setString(5, item.category);
                        pstmt.setInt(6, item.isDirectory ? 1 : 0);
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                }
                conn.commit();
                System.out.println("[DB] SUCCESS: Synchronized metadata with SQLite.");
            }
        } catch (ClassNotFoundException e) {
            System.out.println("[DB] WARNING: No SQLite driver found.");
        } catch (SQLException e) {
            logError("SQLite database error", e);
        }
        saveToCsvFallback();
    }

    private static void saveToCsvFallback() {
        File csvFile = new File("database_index.csv");
        try (PrintWriter writer = new PrintWriter(new FileWriter(csvFile))) {
            writer.println("id,name,path,size,category,isDirectory");
            for (Map.Entry<String, IndexedItem> entry : fileIndex.entrySet()) {
                IndexedItem item = entry.getValue();
                writer.printf("%s,%s,%s,%d,%s,%b\n",
                        item.id,
                        item.name,
                        item.file.getPath().replace("\\", "/"),
                        item.size,
                        item.category,
                        item.isDirectory);
            }
        } catch (IOException e) {
            logError("Error writing to CSV fallback file", e);
        }
    }

    private static void handle(Socket socket) {
        String clientAddress = socket.getRemoteSocketAddress().toString();
        log("New connection from: " + clientAddress + ". Active sessions: " + activeClients.get());

        boolean isAdminAuthenticated = false;

        try (
                DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))
        ) {
            // Handshake
            byte[] magic = new byte[10];
            in.readFully(magic);
            if (!new String(magic).equals("HELLO MTPT")) {
                logWarning("Client " + clientAddress + " failed Handshake.");
                return;
            }

            out.writeByte(1);
            out.flush();

            while (true) {
                int cmd = in.read();
                if (cmd == -1) break;

                // 1. ADMIN AUTHORIZATION
                if (cmd == CMD_ADMIN_AUTH) {
                    String passwordAttempt = in.readUTF();
                    if (passwordAttempt.equals(ADMIN_PASSWORD)) {
                        isAdminAuthenticated = true;
                        out.writeByte(1);
                        log("[ADMIN] Successfully authorized session from " + clientAddress + ".");
                    } else {
                        out.writeByte(0);
                        logWarning("[ADMIN] Invalid authorization attempt from " + clientAddress);
                    }
                    out.flush();
                }

                // 2. PSEUDO-SSH COMMAND EXECUTION
                else if (cmd == CMD_ADMIN_CMD) {
                    String systemCommand = in.readUTF();
                    if (!isAdminAuthenticated) {
                        logWarning("[ADMIN] Attempted unauthorized command execution by client " + clientAddress);
                        byte[] rejectMsg = "Error: No admin privileges. Please login first.".getBytes();
                        out.writeLong(rejectMsg.length);
                        out.write(rejectMsg);
                        out.flush();
                        continue;
                    }

                    log("[ADMIN] Client " + clientAddress + " executing system command: '" + systemCommand + "'");
                    String output = executeSystemCommand(systemCommand);
                    byte[] responseBytes = output.getBytes(Charset.defaultCharset());

                    out.writeLong(responseBytes.length);
                    out.write(responseBytes);
                    out.flush();
                }

                // 3. SPEED TEST
                else if (cmd == CMD_SPEED_TEST) {
                    log("Starting speedtest for " + clientAddress);
                    int size = 5 * 1024 * 1024;
                    out.writeInt(size);
                    out.flush();

                    byte[] buffer = new byte[16384];
                    int total = 0;
                    while (total < size) {
                        int chunk = Math.min(buffer.length, size - total);
                        out.write(buffer, 0, chunk);
                        total += chunk;
                    }
                    out.flush();

                    float clientSpeed = in.readFloat();
                    log(String.format("Speedtest completed for %s. Result: %.2f MB/s", clientAddress, clientSpeed));
                }

                // 4. FILE TRANSFER WITH DYNAMIC ZIP COMPRESSION & CATEGORIES
                else if (cmd == CMD_GET_FILE) {
                    String fileQuery = in.readUTF();
                    log("Client " + clientAddress + " requested target: '" + fileQuery + "'");

                    IndexedItem item = findItem(fileQuery);

                    if (item == null) {
                        logWarning("File or Category/ID target '" + fileQuery + "' not found.");
                        out.writeLong(-1);
                        out.flush();
                        continue;
                    }

                    File fileToSend = item.file;
                    boolean isTemporaryZip = false;

                    // If it is a directory package, zip it on-the-fly
                    if (item.isDirectory) {
                        log("Packaging directory '" + item.file.getName() + "' to ZIP on-the-fly...");
                        try {
                            File tempDir = new File("storage/temp");
                            File tempZipFile = File.createTempFile("pkg_" + item.id + "_", ".zip", tempDir);
                            zipDirectory(item.file, tempZipFile);
                            fileToSend = tempZipFile;
                            isTemporaryZip = true;
                        } catch (IOException e) {
                            logError("Failed compressing package directory: " + item.file.getName(), e);
                            out.writeLong(-1);
                            out.flush();
                            continue;
                        }
                    }

                    long size = fileToSend.length();
                    out.writeLong(size);
                    out.writeUTF(item.isDirectory ? item.file.getName() + ".zip" : item.name);
                    out.flush();

                    log("Transferring '" + fileToSend.getName() + "' (" + size + " bytes) to " + clientAddress);

                    diskSemaphore.acquire();
                    long startTransferTime = System.currentTimeMillis();
                    try (
                            FileInputStream fis = new FileInputStream(fileToSend);
                            FileChannel fileChannel = fis.getChannel()
                    ) {
                        WritableByteChannel socketChannel = Channels.newChannel(out);

                        long transferred = 0;
                        while (transferred < size) {
                            long sent = fileChannel.transferTo(transferred, size - transferred, socketChannel);
                            if (sent <= 0) break;
                            transferred += sent;
                        }
                        out.flush();

                        long duration = System.currentTimeMillis() - startTransferTime;
                        double speed = duration > 0 ? ((double) size / 1024 / 1024) / ((double) duration / 1000) : 0;
                        log(String.format("File '%s' successfully sent to client %s (Duration: %d ms, speed: %.2f MB/s) [Zero-Copy]",
                                fileToSend.getName(), clientAddress, duration, speed));
                    } finally {
                        diskSemaphore.release();
                        if (isTemporaryZip) {
                            fileToSend.delete(); // Clean up the temp zip file to free disk space
                        }
                    }
                }
            }

        } catch (EOFException e) {
            log("Client " + clientAddress + " closed TCP session.");
        } catch (Exception e) {
            logError("Error interacting with client " + clientAddress, e);
        } finally {
            activeClients.decrementAndGet();
            try { socket.close(); } catch (IOException ignored) {}
            log("Connection with " + clientAddress + " closed. Active clients: " + activeClients.get());
        }
    }

    private static IndexedItem findItem(String query) {
        String cleaned = query.trim();
        String categoryScope = null;
        String identifier = cleaned;

        // Parse search scope (supports separators like: '/', ':', or a space ' ')
        int separatorIndex = -1;
        if (cleaned.contains("/")) {
            separatorIndex = cleaned.indexOf("/");
        } else if (cleaned.contains(":")) {
            separatorIndex = cleaned.indexOf(":");
        } else if (cleaned.contains(" ")) {
            separatorIndex = cleaned.indexOf(" ");
        }

        if (separatorIndex != -1) {
            categoryScope = cleaned.substring(0, separatorIndex).trim();
            identifier = cleaned.substring(separatorIndex + 1).trim();
        }

        final String finalCategory = categoryScope;
        final String finalIdentifier = identifier;

        if (finalCategory != null) {
            // Category-scoped query
            return fileIndex.values().stream()
                    .filter(item -> item.category.equalsIgnoreCase(finalCategory))
                    .filter(item -> item.id.equals(finalIdentifier)
                            || item.name.equalsIgnoreCase(finalIdentifier)
                            || (item.isDirectory && item.file.getName().equalsIgnoreCase(finalIdentifier)))
                    .findFirst()
                    .orElse(null);
        } else {
            // Global search query
            // 1. Match by exact system generated ID
            IndexedItem item = fileIndex.get(finalIdentifier);
            if (item != null) return item;

            // 2. Match by exact file name or directory name
            return fileIndex.values().stream()
                    .filter(i -> i.name.equalsIgnoreCase(finalIdentifier)
                            || (i.isDirectory && i.file.getName().equalsIgnoreCase(finalIdentifier)))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void zipDirectory(File folder, File zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            Path sourcePath = folder.toPath();
            Files.walk(sourcePath)
                    .filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
                        // Keep folder hierarchy inside the zip archive relative to parent
                        ZipEntry zipEntry = new ZipEntry(sourcePath.relativize(path).toString().replace("\\", "/"));
                        try {
                            zos.putNextEntry(zipEntry);
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException e) {
                            System.err.println("[ZIP ERROR] Error adding file: " + path + " | " + e.getMessage());
                        }
                    });
        }
    }

    private static void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    private static String executeSystemCommand(String command) {
        StringBuilder output = new StringBuilder();
        try {
            String[] shell;
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                shell = new String[]{"cmd.exe", "/c", command};
            } else {
                shell = new String[]{"/bin/sh", "-c", command};
            }

            ProcessBuilder pb = new ProcessBuilder(shell);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            process.waitFor();
        } catch (Exception e) {
            output.append("Error executing command: ").append(e.getMessage()).append("\n");
        }
        return output.toString();
    }

    private static void printNetworkDiagnostics(int port) {
        String localIp = "Unknown";
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;

                java.util.Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        localIp = addr.getHostAddress();
                        System.out.println("  Local IP (LAN): " + localIp + " (Adapter: " + iface.getDisplayName() + ")");
                    }
                }
            }
        } catch (Exception e) {
            logError("Could not determine local IP address.", e);
        }

        String publicIp = "Unknown";
        try {
            URI uri = new URI("https://ident.me");
            URL url = uri.toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                publicIp = reader.readLine().trim();
            }
            System.out.println("  External IP (WAN): " + publicIp);
        } catch (Exception e) {
            System.out.println("  External IP (WAN): Failed to retrieve");
        }
    }
}