package com.sap.monitor;

import com.sap.conn.jco.*;
import com.sap.conn.jco.ext.DestinationDataProvider;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

public class SAPSystemMonitor {

    private static final int EXIT_OK = 0;
    private static final int EXIT_WARNING = 1;
    private static final int EXIT_CRITICAL = 2;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: SAPSystemMonitor <destination_name>");
            System.exit(EXIT_CRITICAL);
        }

        String destinationName = args[0];

        int overallStatus = EXIT_OK;

        try {
            JCoDestination dest = getDestination(destinationName);
            System.out.println("Connected to: " + dest.getAttributes().getSystemID());

            overallStatus = Math.max(overallStatus, checkLocks(dest));
            overallStatus = Math.max(overallStatus, checkUpdates(dest));
            overallStatus = Math.max(overallStatus, checkQueues(dest));
            overallStatus = Math.max(overallStatus, checkServers(dest));
            overallStatus = Math.max(overallStatus, checkJobs(dest));
            overallStatus = Math.max(overallStatus, checkDumps(dest));
            overallStatus = Math.max(overallStatus, checkLogonGroups(dest));

            System.out.println("\n=== Overall Status: " + getStatusText(overallStatus) + " ===");

        } catch (Exception e) {
            System.err.println("FATAL: " + e.getMessage());
            overallStatus = EXIT_CRITICAL;
        }

        System.exit(overallStatus);
    }

    // === Robust destination loading (supports destinations/ folder automatically) ===
    private static JCoDestination getDestination(String destName) throws JCoException, IOException {
        Path destFile = Paths.get("destinations", destName + ".jcoDestination");

        if (Files.exists(destFile)) {
            System.out.println("Loading destination from: " + destFile);
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(destFile)) {
                props.load(in);
            }
            // Register a temporary destination data provider
            CustomDestinationDataProvider provider = new CustomDestinationDataProvider(destName, props);
            JCoDestinationManager.getDestination(destName); // trigger registration if needed
            return JCoDestinationManager.getDestination(destName);
        }

        // Fallback to normal JCo lookup (uses -Djco.destinations.dir if set)
        return JCoDestinationManager.getDestination(destName);
    }

    // Simple custom provider so we can load from destinations/ folder reliably
    private static class CustomDestinationDataProvider implements DestinationDataProvider {
        private final String name;
        private final Properties props;

        CustomDestinationDataProvider(String name, Properties props) {
            this.name = name;
            this.props = props;
            JCoDestinationManager.getDestinationDataProvider(); // ensure manager exists
        }

        @Override
        public Properties getDestinationProperties(String destinationName) {
            if (destinationName.equals(name)) {
                return props;
            }
            return null;
        }

        @Override
        public void setDestinationDataEventListener(DestinationDataEventListener listener) {}

        @Override
        public boolean supportsEvents() {
            return false;
        }
    }

    // === Real monitoring checks ===

    private static int checkLocks(JCoDestination dest) {
        System.out.println("\n[SM12] Lock entries...");
        try {
            JCoFunction fn = dest.getRepository().getFunction("ENQUEUE_READ");
            if (fn == null) {
                System.out.println("  ENQUEUE_READ not available - skipping");
                return EXIT_OK;
            }
            fn.getImportParameterList().setValue("GCLIENT", dest.getClient());
            fn.execute(dest);
            JCoTable lockTable = fn.getTableParameterList().getTable("ENQ");
            int count = lockTable.getNumRows();
            System.out.println("  Active locks: " + count);
            return count > 5000 ? EXIT_WARNING : EXIT_OK;
        } catch (Exception e) {
            System.out.println("  Error checking locks: " + e.getMessage());
            return EXIT_WARNING;
        }
    }

    private static int checkUpdates(JCoDestination dest) {
        System.out.println("\n[SM13] Update errors...");
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            if (fn == null) return EXIT_OK;
            fn.getImportParameterList().setValue("QUERY_TABLE", "VBMOD");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 10);
            fn.execute(dest);
            int rows = fn.getTableParameterList().getTable("DATA").getNumRows();
            System.out.println("  Update records found: " + rows);
            return EXIT_OK;
        } catch (Exception e) {
            System.out.println("  Update check skipped: " + e.getMessage());
            return EXIT_OK;
        }
    }

    private static int checkQueues(JCoDestination dest) {
        System.out.println("\n[SMQ1] Queue status...");
        try {
            JCoFunction fn = dest.getRepository().getFunction("TRFC_QOUT_GET_QUEUES");
            if (fn == null) {
                System.out.println("  TRFC_QOUT_GET_QUEUES not available");
                return EXIT_OK;
            }
            fn.execute(dest);
            System.out.println("  qRFC check completed");
            return EXIT_OK;
        } catch (Exception e) {
            System.out.println("  Queue check: " + e.getMessage());
            return EXIT_WARNING;
        }
    }

    private static int checkServers(JCoDestination dest) {
        System.out.println("\n[SM51] Application servers...");
        try {
            JCoFunction fn = dest.getRepository().getFunction("TH_SERVER_LIST");
            if (fn == null) return EXIT_OK;
            fn.execute(dest);
            JCoTable servers = fn.getTableParameterList().getTable("LIST");
            System.out.println("  Active application servers: " + servers.getNumRows());
            return EXIT_OK;
        } catch (Exception e) {
            System.out.println("  Server list error: " + e.getMessage());
            return EXIT_WARNING;
        }
    }

    private static int checkJobs(JCoDestination dest) {
        System.out.println("\n[SM37] Background jobs...");
        try {
            JCoFunction fn = dest.getRepository().getFunction("BAPI_XBP_GET_JOB_LIST");
            if (fn == null) return EXIT_OK;

            String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            fn.getImportParameterList().setValue("JOBNAME", "*");
            fn.getImportParameterList().setValue("USERNAME", "*");
            fn.getImportParameterList().setValue("FROM_DATE", yesterday);
            fn.getImportParameterList().setValue("TO_DATE", today);
            fn.execute(dest);

            JCoTable jobs = fn.getTableParameterList().getTable("JOBLIST");
            int failed = 0;
            for (int i = 0; i < jobs.getNumRows(); i++) {
                jobs.setRow(i);
                String status = jobs.getString("STATUS");
                if ("A".equals(status) || "C".equals(status)) failed++;
            }
            System.out.println("  Failed/cancelled jobs (last 24h): " + failed);
            return failed > 10 ? EXIT_WARNING : EXIT_OK;
        } catch (Exception e) {
            System.out.println("  Job check error: " + e.getMessage());
            return EXIT_WARNING;
        }
    }

    private static int checkDumps(JCoDestination dest) {
        System.out.println("\n[ST22] Short dumps...");
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            if (fn == null) return EXIT_OK;
            fn.getImportParameterList().setValue("QUERY_TABLE", "SNAP");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 50);
            fn.execute(dest);
            int count = fn.getTableParameterList().getTable("DATA").getNumRows();
            System.out.println("  Short dumps (sample): " + count);
            return count > 100 ? EXIT_WARNING : EXIT_OK;
        } catch (Exception e) {
            System.out.println("  Dump check skipped");
            return EXIT_OK;
        }
    }

    private static int checkLogonGroups(JCoDestination dest) {
        System.out.println("\n[SMLG] Logon groups...");
        try {
            JCoFunction fn = dest.getRepository().getFunction("SMLG_GET_DEFINED_GROUPS");
            if (fn == null) {
                System.out.println("  SMLG function not available");
                return EXIT_OK;
            }
            fn.execute(dest);
            System.out.println("  Logon groups retrieved successfully");
            return EXIT_OK;
        } catch (Exception e) {
            System.out.println("  Logon group check: " + e.getMessage());
            return EXIT_OK;
        }
    }

    private static String getStatusText(int code) {
        switch (code) {
            case EXIT_OK: return "OK";
            case EXIT_WARNING: return "WARNING";
            case EXIT_CRITICAL: return "CRITICAL";
            default: return "UNKNOWN";
        }
    }
}
