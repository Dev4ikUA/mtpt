# MTPT - File transfering protocol on Java 21

MTPT: High-Performance Hybrid Server & CLI ClientMTPT is a custom, high-performance client-server file transfer protocol written in Java. The project is engineered with a strict focus on maximizing I/O speed, network efficiency, and providing convenient server administration through an interactive console interface.

🚀 Key Features
⚡ Zero-Copy Transfer: Utilizes Java NIO (FileChannel.transferTo()) to stream files directly from the OS kernel page cache to the socket, bypassing JVM user-space buffer copying for maximum throughput.

🧵 Virtual Threads (Project Loom):
Powered by Java's modern virtual thread-per-task executor. It handles thousands of concurrent sessions effortlessly with an extremely lightweight memory footprint.

📦 Dynamic On-the-Fly Packaging:
Supports compressing entire directories or nested subdirectories into .zip archives instantly when requested by the client.
Temporary archives are cleaned up automatically as soon as the transfer completes.

📁 Categories & Smart Indexer:
Scans the storage directory on startup. Root directories serve as category scopes (e.g., ISO), while any nested subdirectories are registered as downloadable sub-packages.

🗃️ SQLite & CSV Metadata Sync:
Automatically indexes files into an SQLite database (mtpt_metadata.db) with a flat-file CSV fallback (database_index.csv) for fast local queries and metadata synchronization.

💻 Pseudo-SSH Terminal (Admin Mode):
Securely execute remote system commands (CMD/Bash) with interactive console output streaming. 
Supports handling Ctrl+C signals to kill hung processes remotely.

⏱️ Built-in Speedtest:
A network diagnostic command to quickly measure real-world LAN bandwidth.

🛠️ Tech StackLanguage:
Java 21 Database: SQLite (via sqlite-jdbc driver)
Network Engine: Raw TCP Sockets, Java NIO (Zero-Copy Channels), Virtual Threads
Build Tool: IntelliJ IDEA (Artifacts configuration for Fat JAR compilation)

🎮 CLI Client
CommandsOnce connected, the interactive client CLI supports the following commands:CommandDescriptionmtpt connect <IP> [port]Establish a connection to the MTPT server (Default port: 5050)mtpt disconnectTerminate the current sessionmtpt speedtestRun a network throughput benchmarkmtpt get <ID>Retrieve a single file by its unique indexed IDmtpt get <Category> <Sub-dir>Pack and download a subdirectory as a ZIP archive (e.g., mtpt get ISO Kingston)mtpt admin <IP> [port] <password>Authenticate and enter the remote Pseudo-SSH shellexitQuit the application
🏗️ Build & Run

1. Building the Runnable JAR (Fat JAR) in IntelliJ IDEATo pack the server with all its dependencies (including the SQLite driver):Open the project in IntelliJ IDEA.Press Ctrl + Alt + Shift + S (Project Structure) -> Artifacts -> click + -> JAR -> From modules with dependencies. Select sunflowerr. MTPTServer as the Main Class, choose extract to the target JAR, and set the META-INF path to your src directory. Build the artifact via the top menu: Build -> Build Artifacts... -> MTPT:jar -> Build.
2. Running the ServerOnce compiled, the artifact is located in the out/artifacts/ folder. You can launch it using:Bashjava -jar out/artifacts/MTPT_jar/MTPT.jar
3. Server Directory StructureEnsure your server directory structure matches the following setup before running:

D:\MTPT\
├── out/
│   └── artifacts/
│       └── MTPT_jar/
│           └── MTPT.jar      <-- Your compiled runnable JAR
├── storage/                  <-- Root storage folder
│   ├── ISO/                  <-- Category directory
│   │   ├── Kingston/         <-- Sub-package (downloadable as zip)
│   │   └── ubuntu.iso        <-- Standard category file
│   └── temp/                 <-- Working directory for temporary ZIPs
├── mtpt_metadata.db          <-- Auto-generated SQLite database
└── server_log.txt            <-- Auto-generated session log file

