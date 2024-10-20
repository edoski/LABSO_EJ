import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;

public class Client {
	private Socket socket;
	private BufferedReader in;
	private PrintWriter out;
	private static Boolean isPublisher = null;
	private static String topic = null;
	private volatile boolean running = true;
	public static boolean isServerInspecting = false;
	private static final ArrayList<String> backlog = new ArrayList<>();
	private static final ArrayList<String> publisherOnlyCommands = new ArrayList<>(Arrays.asList("send", "list"));
	private static final ArrayList<String> disabledWhenInspecting = new ArrayList<>(Arrays.asList("send", "list", "listall"));

	public Client(Socket socket) {
		try {
			this.socket = socket;
			this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			this.out = new PrintWriter(socket.getOutputStream(), true);
		} catch (IOException e) {
			closeEverything();
		}
	}

	private void start() {
        System.out.println(
                "--- CONNECTED TO SERVER ON PORT " + socket.getPort() + " ---\n" +
                "> Enter 'help' for a list of available commands.\n"
        );
        receiveMessage(); // Start the receiveMessage thread

        try (Scanner scanner = new Scanner(System.in)) {
            while (running) { // Use the main thread for input handling
                if (!running) break;
				processCommand(scanner.nextLine());
            }
        } catch (Exception e) {
            if (running) {
                System.out.println("> Error reading from console: " + e.getMessage());
                running = false;
                closeEverything();
            }
        }
    }

	private void receiveMessage() {
		new Thread(() -> {
			while (running && !socket.isClosed()) {
				try {
					String messageFromServer = in.readLine();
					if (messageFromServer == null) {
						closeEverything();
						break; // Server disconnected
					}
					handleMessageFromServer(messageFromServer);
				} catch (IOException e) {
					if (running) {
						System.out.println("> Connection lost: " + e.getMessage());
						running = false;
						closeEverything();
					}
					break;
				}
			}
		}).start();
	}

	// Heart of the client, processes the input commands
	// todo refactor this and ClientHandler inputs so that you send sanitized tokens[] directly instead of inputLine
	private void processCommand(String inputLine) {
		if (inputLine.isEmpty()) return;
		// Sanitize input, split by whitespace
		String[] tokens = inputLine.trim().split("\\s+");
		String command = tokens[0].toLowerCase();

		// If the server is inspecting, verify & queue the command for later execution
		if (isServerInspecting && disabledWhenInspecting.contains(command)) {
			if (!isPublisher && publisherOnlyCommands.contains(command)) {
				System.out.println("> You cannot use the command '" + command + "' as a subscriber.\n");
			} else {
				System.out.println(command.startsWith("listall")
					? "> Command '" + inputLine + "' will execute last (to avoid data inconsistencies) when Inspect mode is ended.\n"
					: "> Command '" + inputLine + "' has been queued and will execute when Inspect mode is ended.\n");
				backlog.add(inputLine);
			}
			return;
		}

		// Commands that use out.println() send a request to the client handler to fulfill the command
		// The rest of the commands are handled entirely or partially locally
		switch (command) {
			case "help" -> showHelp();
			case "show" -> out.println("show");
			case "send" -> handleSendCommand(tokens);
			case "list" -> out.println("list");
			case "listall" -> out.println("listall");
			case "quit" -> {
				out.println("quit");
				closeEverything();
			}
			case "publish", "subscribe" -> handleRegistration(tokens);
			default -> System.out.println("> Unknown command. Enter 'help' to see the list of available commands.\n");
		}
	}

	private void showHelp() {
		System.out.println("--- HELP: AVAILABLE COMMANDS ---");
		if (isPublisher == null) {
			System.out.println("> [publish | subscribe] <topic>: Register as a publisher (read & write) or subscriber (read-only) for <topic>");
		} else {
			if (isPublisher) { // Only publishers can use these commands
				System.out.println(
						(isServerInspecting ? "* " : "") + "> send <message>: Send a message to the server\n" +
						(isServerInspecting ? "* " : "") + "> list: List the messages you have sent in the topic"
				);
			}
			// Only registered clients (both publishers & subscribers) can use this command
			System.out.println((isServerInspecting ? "* " : "") + "> listall: List all messages in the topic");
		}
		// All clients (registered & unregistered) can use these commands
		System.out.println((isServerInspecting ? "  " : "") + "> show: Show available topics");
		System.out.println((isServerInspecting ? "  " : "") + "> quit: Disconnect from the server\n");
		if (isServerInspecting) {
			System.out.println(
					"""
					* Commands marked with an asterisk (*) are disabled during Inspect mode.
					! N.B. Any usage of (*) will be queued and will execute once Inspect mode is ended.
					"""
			);
		}
	}

	private void handleSendCommand(String[] tokens) {
		if (isPublisher == null || !isPublisher) {
			System.out.println(isPublisher == null
					? "> You need to register as a publisher first.\n"
					: "> You are registered as a subscriber. You cannot send messages.\n");
			return;
		}

		if (tokens.length < 2) {
			System.out.println("> Usage: send <message>\n");
			return;
		}

		// Combine tokens to form the message in case of multiple words
		String message = String.join(" ", Arrays.copyOfRange(tokens, 1, tokens.length));
		out.println(message);
	}

	private void handleRegistration(String[] tokens) {
		if (isPublisher != null) {
			System.out.println("> You have already registered as '" + (isPublisher ? "publisher" : "subscriber") + "' for topic '" + topic + "'.\n");
			return;
		}

		if (tokens.length < 2) {
			System.out.println("> Usage: " + tokens[0] + " <topic_name>\n");
			return;
		}

		// Send registration command to the server
		out.println(String.join(" ", tokens));

		String role = tokens[0].toLowerCase();
		isPublisher = role.equals("publish");
		topic = String.join("_", Arrays.copyOfRange(tokens, 1, tokens.length)); // "example topic" -> "example_topic"
	}

	private void handleMessageFromServer(String messageFromServer) {
		String[] tokens = messageFromServer.split("\\s+");
		if (tokens[0].equals("IS_SERVER_INSPECTING")) {
			isServerInspecting = Boolean.parseBoolean(tokens[1]);
			if (!isServerInspecting) executeBacklogCommands();
			return;
		}

		System.out.println(messageFromServer);
	}

	private void executeBacklogCommands() {
		if (backlog.isEmpty()) return;
		synchronized (backlog) {
			backlog.sort((a, b) -> { // Sort "list" and "listall" commands to be executed last to avoid interleaving
				if (a.startsWith("list") && !b.startsWith("list")) return 1;
				if (!a.startsWith("list") && b.startsWith("list")) return -1;
				return 0;
			});

			System.out.println("--- COMMANDS TO BE EXECUTED ---");
			for (String cmd : backlog) System.out.println("> " + (backlog.indexOf(cmd) + 1) + ": " + cmd);
			System.out.println();

			for (String cmd : backlog) processCommand(cmd);
			backlog.clear();
		}
	}

	private void closeEverything() {
		running = false;
		try {
			if (socket != null && !socket.isClosed()) socket.close();
			if (in != null) in.close();
			if (out != null) out.close();
			System.out.println("--- CLIENT SHUTDOWN ---");
			System.exit(0);
		} catch (IOException e) {
			System.out.println("> Error closing resources: " + e.getMessage());
		}
	}

	public static void main(String[] args) {
		if (args.length < 2) {
			System.err.println("> Usage: java Client <hostname> <port>");
			return;
		}

		try {
			Socket socket = new Socket(args[0], Integer.parseInt(args[1]));
			Client client = new Client(socket);
			client.start();
		} catch (IOException e) {
			System.out.println("> Unable to connect to the server.");
		}
	}
}