import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/**
 * TicketEscalationSimulator.java
 *
 * Single-file simulator demonstrating ticket lifecycle, SLA checking, auto-escalation, and worker processing.
 *
 * How to run:
 *   javac TicketEscalationSimulator.java
 *   java TicketEscalationSimulator
 *
 * Commands in console:
 *   add                -> interactive prompt to create a ticket
 *   list               -> list open tickets
 *   resolve <id>       -> resolve ticket by id
 *   stats              -> show stats
 *   save               -> save state to disk
 *   load               -> load saved state
 *   exit               -> save automatically & exit
 *
 * This code uses Java 8+ APIs (java.time, Executors).
 */
public class TicketEscalationSimulator {

    // ----- Ticket model -----
    public static class Ticket implements Serializable {
        private static final long serialVersionUID = 1L;

        public enum Status { OPEN, IN_PROGRESS, RESOLVED, ESCALATED }

        public final String id;
        public final String title;
        public final String description;
        public volatile int priority; // 1 (highest) .. 5 (lowest)
        public final Instant createdAt;
        public volatile Instant slaDueAt;
        public volatile Status status;
        public volatile String assignedTo; // worker or queue name
        public volatile int escalations; // number of times escalated

        public Ticket(String title, String description, int priority, Duration slaDuration) {
            this.id = UUID.randomUUID().toString().substring(0, 8);
            this.title = title;
            this.description = description;
            this.priority = clampPriority(priority);
            this.createdAt = Instant.now();
            this.slaDueAt = Instant.now().plus(slaDuration);
            this.status = Status.OPEN;
            this.assignedTo = "Unassigned";
            this.escalations = 0;
        }

        private int clampPriority(int p) {
            if (p < 1) return 1;
            if (p > 5) return 5;
            return p;
        }

        public synchronized void escalate() {
            if (priority > 1) priority = priority - 1; // higher urgency => lower number
            escalations++;
            status = Status.ESCALATED;
            // push SLA forward slightly to allow action
            slaDueAt = Instant.now().plus(Duration.ofHours(1));
        }

        @Override
        public String toString() {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
            return String.format("Ticket[%s] %s | P:%d | status:%s | assigned:%s | sla:%s | esc:%d",
                    id, trim(title, 40), priority, status, assignedTo, fmt.format(slaDueAt), escalations);
        }

        private String trim(String s, int n) {
            if (s.length() <= n) return s;
            return s.substring(0, n-3) + "...";
        }
    }

    // ----- Comparator for ticket priority queue -----
    // Tickets with lower priority number are more urgent (1 highest), and older createdAt first.
    public static class TicketComparator implements Comparator<Ticket>, Serializable {
        private static final long serialVersionUID = 1L;
        @Override
        public int compare(Ticket a, Ticket b) {
            int p = Integer.compare(a.priority, b.priority);
            if (p != 0) return p;
            return a.createdAt.compareTo(b.createdAt);
        }
    }

    // ----- TicketManager -----
    public static class TicketManager implements Serializable {
        private static final long serialVersionUID = 1L;

        // transient fields are not serialized (recreated after load)
        private transient PriorityBlockingQueue<Ticket> queue;
        private transient Map<String, Ticket> allTickets; // id -> ticket
        private transient ReentrantLock lock;

        // persisted backing list (for serialization)
        private List<Ticket> persistedTickets = new ArrayList<>();

        public TicketManager() {
            initTransients();
        }

        private void initTransients() {
            this.queue = new PriorityBlockingQueue<>(100, new TicketComparator());
            this.allTickets = new ConcurrentHashMap<>();
            this.lock = new ReentrantLock();
            // populate from persistedTickets if any
            if (persistedTickets != null) {
                for (Ticket t : persistedTickets) {
                    allTickets.put(t.id, t);
                    if (t.status == Ticket.Status.OPEN || t.status == Ticket.Status.ESCALATED || t.status == Ticket.Status.IN_PROGRESS) {
                        queue.offer(t);
                    }
                }
            }
        }

        public synchronized void addTicket(Ticket t) {
            persistedTickets.add(t);
            allTickets.put(t.id, t);
            queue.offer(t);
            System.out.println("Added: " + t);
        }

        public Ticket pollTicket(long timeoutMs) throws InterruptedException {
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }

        public Ticket getById(String id) {
            return allTickets.get(id);
        }

        public List<Ticket> listOpen() {
            List<Ticket> res = new ArrayList<>();
            for (Ticket t : allTickets.values()) {
                if (t.status == Ticket.Status.OPEN || t.status == Ticket.Status.IN_PROGRESS || t.status == Ticket.Status.ESCALATED) {
                    res.add(t);
                }
            }
            res.sort(new TicketComparator());
            return res;
        }

        public synchronized void resolve(String id, String resolver) {
            Ticket t = allTickets.get(id);
            if (t == null) { System.out.println("Ticket not found: " + id); return; }
            t.status = Ticket.Status.RESOLVED;
            t.assignedTo = resolver;
            // remove from queue if present (remove uses equals)
            queue.removeIf(ticket -> ticket.id.equals(id));
            System.out.println("Resolved: " + id + " by " + resolver);
        }

        // escalate tickets past SLA
        public synchronized List<Ticket> escalatePastSla() {
            List<Ticket> escalated = new ArrayList<>();
            Instant now = Instant.now();
            for (Ticket t : allTickets.values()) {
                if ((t.status == Ticket.Status.OPEN || t.status == Ticket.Status.IN_PROGRESS) && t.slaDueAt.isBefore(now)) {
                    t.escalate();
                    t.assignedTo = "EscalationQueue";
                    // reinsert into queue so priority ordering updates
                    queue.removeIf(ticket -> ticket.id.equals(t.id));
                    queue.offer(t);
                    escalated.add(t);
                    System.out.println("[ESCALATED] " + t.id + " new priority=" + t.priority);
                }
            }
            return escalated;
        }

        // persistence: save to file
        public synchronized void saveTo(File f) throws IOException {
            // ensure persistedTickets matches allTickets
            persistedTickets = new ArrayList<>(allTickets.values());
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f))) {
                oos.writeObject(this);
            }
            System.out.println("Saved state to " + f.getAbsolutePath());
        }

        public static TicketManager loadFrom(File f) throws IOException, ClassNotFoundException {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
                TicketManager tm = (TicketManager) ois.readObject();
                tm.initTransients();
                System.out.println("Loaded state from " + f.getAbsolutePath());
                return tm;
            }
        }
    }

    // ----- Worker thread (simulates agent resolving tickets) -----
    public static class Worker implements Runnable {
        private final String name;
        private final TicketManager manager;
        private final Random rng = new Random();

        private volatile boolean running = true;

        public Worker(String name, TicketManager manager) {
            this.name = name;
            this.manager = manager;
        }

        public void stop() { running = false; }

        @Override
        public void run() {
            while (running) {
                try {
                    Ticket t = manager.pollTicket(2000); // wait for ticket
                    if (t == null) {
                        // idle
                        Thread.sleep(500);
                        continue;
                    }

                    // claim ticket
                    if (t.status == Ticket.Status.RESOLVED) continue;
                    t.status = Ticket.Status.IN_PROGRESS;
                    t.assignedTo = name;
                    System.out.println(name + " picked " + t.id + " (P:" + t.priority + ")");

                    // simulate work time proportional to priority (higher priority -> quicker)
                    long workMs = 1500 + rng.nextInt(2000) + (t.priority * 400L);
                    Thread.sleep(workMs);

                    // randomly decide to resolve or requeue (simulate needing more info)
                    if (rng.nextDouble() < 0.8) {
                        manager.resolve(t.id, name);
                    } else {
                        // put back to queue with slight delay and updated SLA
                        t.status = Ticket.Status.OPEN;
                        t.slaDueAt = Instant.now().plus(Duration.ofHours(2));
                        manager.queue.offer(t);
                        System.out.println(name + " requeued " + t.id);
                    }

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    System.err.println("Worker error: " + ex.getMessage());
                }
            }
            System.out.println(name + " stopped.");
        }
    }

    // ----- Escalation Monitor using ScheduledExecutorService -----
    public static class EscalationMonitor implements Runnable {
        private final TicketManager manager;

        public EscalationMonitor(TicketManager manager) {
            this.manager = manager;
        }

        @Override
        public void run() {
            try {
                List<Ticket> escalated = manager.escalatePastSla();
                if (!escalated.isEmpty()) {
                    // In real system, notify via email/Slack/Chatter; here we console log
                    System.out.println("EscalationMonitor: escalated " + escalated.size() + " tickets.");
                }
            } catch (Exception ex) {
                System.err.println("EscalationMonitor error: " + ex.getMessage());
            }
        }
    }

    // ----- Simple console UI & main -----
    public static void main(String[] args) throws Exception {
        System.out.println("Ticket Escalation Simulator v1.0");
        TicketManager manager;
        File saveFile = new File("tickets_state.bin");
        if (saveFile.exists()) {
            try {
                manager = TicketManager.loadFrom(saveFile);
            } catch (Exception e) {
                System.err.println("Failed to load state; starting fresh. " + e.getMessage());
                manager = new TicketManager();
            }
        } else {
            manager = new TicketManager();
            // seed with sample tickets
            seedSampleTickets(manager);
        }

        // start worker threads
        List<Worker> workers = new ArrayList<>();
        List<Thread> workerThreads = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Worker w = new Worker("Agent-" + i, manager);
            Thread t = new Thread(w, "Agent-" + i);
            t.start();
            workers.add(w);
            workerThreads.add(t);
        }

        // start escalation monitor scheduled every 30 seconds
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(new EscalationMonitor(manager), 10, 30, TimeUnit.SECONDS);

        // Console input loop
        try (Scanner sc = new Scanner(System.in)) {
            printHelp();
            while (true) {
                System.out.print("> ");
                String line = sc.nextLine();
                if (line == null) break;
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                String cmd = parts[0].toLowerCase();

                if (cmd.equals("exit") || cmd.equals("quit")) {
                    System.out.println("Shutting down...");
                    // stop workers
                    for (Worker w : workers) w.stop();
                    for (Thread t : workerThreads) t.interrupt();
                    scheduler.shutdownNow();
                    manager.saveTo(saveFile);
                    break;
                } else if (cmd.equals("help")) {
                    printHelp();
                } else if (cmd.equals("add")) {
                    interactiveAdd(manager, sc);
                } else if (cmd.equals("list")) {
                    listTickets(manager);
                } else if (cmd.equals("resolve")) {
                    if (parts.length < 2) {
                        System.out.println("Usage: resolve <ticketId>");
                    } else {
                        manager.resolve(parts[1], "ManualResolver");
                    }
                } else if (cmd.equals("save")) {
                    manager.saveTo(saveFile);
                } else if (cmd.equals("load")) {
                    try {
                        TicketManager loaded = TicketManager.loadFrom(saveFile);
                        manager = loaded;
                        System.out.println("State loaded. Note: running workers still reference previous manager in this demo; restart recommended to bind workers to new manager.");
                    } catch (Exception e) {
                        System.err.println("Load failed: " + e.getMessage());
                    }
                } else if (cmd.equals("stats")) {
                    showStats(manager);
                } else {
                    System.out.println("Unknown command. Type 'help' for commands.");
                }
            }
        } finally {
            // ensure graceful shutdown
            for (Worker w : workers) w.stop();
            for (Thread t : workerThreads) t.interrupt();
            scheduler.shutdownNow();
        }
    }

    private static void printHelp() {
        System.out.println("Commands: add | list | resolve <id> | stats | save | load | help | exit");
    }

    private static void interactiveAdd(TicketManager manager, Scanner sc) {
        try {
            System.out.print("Title: ");
            String title = sc.nextLine().trim();
            System.out.print("Description: ");
            String desc = sc.nextLine().trim();
            System.out.print("Priority (1-5, 1 highest) [3]: ");
            String p = sc.nextLine().trim();
            int pr = p.isEmpty() ? 3 : Integer.parseInt(p);
            System.out.print("SLA hours [4]: ");
            String s = sc.nextLine().trim();
            int slaH = s.isEmpty() ? 4 : Integer.parseInt(s);
            Ticket t = new Ticket(title, desc, pr, Duration.ofHours(slaH));
            manager.addTicket(t);
        } catch (Exception ex) {
            System.err.println("Failed to add ticket: " + ex.getMessage());
        }
    }

    private static void listTickets(TicketManager manager) {
        List<Ticket> open = manager.listOpen();
        System.out.println("Open Tickets (" + open.size() + "):");
        for (Ticket t : open) {
            System.out.println("  " + t);
        }
    }

    private static void showStats(TicketManager manager) {
        List<Ticket> open = manager.listOpen();
        long escalated = open.stream().filter(t -> t.status == Ticket.Status.ESCALATED).count();
        long inProgress = open.stream().filter(t -> t.status == Ticket.Status.IN_PROGRESS).count();
        long openOnly = open.stream().filter(t -> t.status == Ticket.Status.OPEN).count();
        long resolvedTotal = manager.persistedTickets.stream().filter(t -> t.status == Ticket.Status.RESOLVED).count();

        System.out.println("Stats:");
        System.out.println("  Open: " + openOnly);
        System.out.println("  InProgress: " + inProgress);
        System.out.println("  Escalated: " + escalated);
        System.out.println("  ResolvedTotal (since start): " + resolvedTotal);
    }

    private static void seedSampleTickets(TicketManager manager) {
        manager.addTicket(new Ticket("Email not delivered", "Customer reports emails not delivered", 3, Duration.ofHours(2)));
        manager.addTicket(new Ticket("API latency spike", "Observed high latency in API endpoints", 2, Duration.ofHours(4)));
        manager.addTicket(new Ticket("Payment failure", "Transactions failing with 502", 1, Duration.ofHours(1)));
        manager.addTicket(new Ticket("UI bug - alignment", "Minor CSS issue on dashboard", 5, Duration.ofDays(2)));
    }
}
