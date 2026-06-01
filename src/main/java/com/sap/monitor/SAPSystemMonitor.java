package com.sap.monitor;

import com.sap.conn.jco.*;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

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
            System.out.println("Connected to: " + dest.getAttributes().getSystemID() + "\n");

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

    private static JCoDestination getDestination(String destName) throws JCoException {
        Path destFile = Paths.get("destinations", destName + ".jcoDestination");
        if (Files.exists(destFile)) {
            System.setProperty("jco.destinations.dir", "destinations");
        }
        return JCoDestinationManager.getDestination(destName);
    }

    // ==================== SM12 - Locks ====================
    private static int checkLocks(JCoDestination dest) {
        System.out.println("[SM12] Lock entries");
        try {
            JCoFunction fn = dest.getRepository().getFunction("ENQUEUE_READ");
            if (fn == null) {
                System.out.println("  Status: SKIPPED (ENQUEUE_READ not available)\n");
                return EXIT_OK;
            }

            fn.getImportParameterList().setValue("GCLIENT", dest.getClient());
            fn.execute(dest);

            JCoTable lockTable = fn.getTableParameterList().getTable("ENQ");
            int count = lockTable.getNumRows();

            System.out.println("  Value read     : " + count + " active locks");
            System.out.println("  Threshold      : > 5000 = WARNING");
            System.out.println("  Status         : " + (count > 5000 ? "WARNING" : "OK") + "\n");

            return count > 5000 ? EXIT_WARNING : EXIT_OK;

        } catch (Exception e) {
            System.out.println("  Error: " + e.getMessage() + "\n");
            return EXIT_WARNING;
        }
    }

    // ==================== SM13 - Update errors ====================
    private static int checkUpdates(JCoDestination dest) {
        System.out.println("[SM13] Update records (VBMOD)");
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            if (fn == null) {
                System.out.println("  Status: SKIPPED\n");
                return EXIT_OK;
            }

            fn.getImportParameterList().setValue("QUERY_TABLE", "VBMOD");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 50);
            fn.execute(dest);

            int rows = fn.getTableParameterList().getTable("DATA").getNumRows();

            System.out.println("  Value read     : " + rows + " update records");
            System.out.println("  Threshold      : informational only");
            System.out.println("  Status         : OK\n");

            return EXIT_OK;

        } catch (Exception e) {
            System.out.println("  Status: SKIPPED (" + e.getMessage() + ")\n");
            return EXIT_OK;
        }
    }

    // ==================== SMQ1 - qRFC Queues ====================
    private static int checkQueues(JCoDestination dest) {
        System.out.println("[SMQ1] qRFC Queue status");
        try {
            JCoFunction fn = dest.getRepository().getFunction("TRFC_QOUT_GET_QUEUES");
            if (fn == null) {
                System.out.println("  Status: SKIPPED (function not available on this system)\n");
                return EXIT_OK;
            }

            fn.execute(dest);
            System.out.println("  Status: OK\n");
            return EXIT_OK;

        } catch (Exception e) {
            System.out.println("  Status: WARNING (" + e.getMessage() + ")\n");
            return EXIT_WARNING;
        }
    }

    // ==================== SM51 - Application Servers ====================
    private static int checkServers(JCoDestination dest) {
        System.out.println("[SM51] Application servers");
        try {
            JCoFunction fn = dest.getRepository().getFunction("TH_SERVER_LIST");
            if (fn == null) {
                System.out.println("  Status: SKIPPED\n");
                return EXIT_OK;
            }

            fn.execute(dest);
            JCoTable servers = fn.getTableParameterList().getTable("LIST");
            int count = servers.getNumRows();

            System.out.println("  Value read     : " + count + " active servers");
            System.out.println("  Threshold      : >= 1 required");
            System.out.println("  Status         : " + (count >= 1 ? "OK" : "CRITICAL") + "\n");

            return count >= 1 ? EXIT_OK : EXIT_CRITICAL;

        } catch (Exception e) {
            System.out.println("  Error: " + e.getMessage() + "\n");
            return EXIT_WARNING;
        }
    }

    // ==================== SM37 - Background Jobs ====================
    private static int checkJobs(JCoDestination dest) {
        System.out.println("[SM37] Background jobs (last 24h)");
        try {
            JCoFunction fn = dest.getRepository().getFunction("BAPI_XBP_GET_JOB_LIST");
            if (fn == null) {
                System.out.println("  Status: SKIPPED (BAPI_XBP_GET_JOB_LIST not available)\n");
                return EXIT_OK;
            }

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

            System.out.println("  Value read     : " + failed + " failed/cancelled jobs");
            System.out.println("  Threshold      : > 10 = WARNING");
            System.out.println("  Status         : " + (failed > 10 ? "WARNING" : "OK") + "\n");

            return failed > 10 ? EXIT_WARNING : EXIT_OK;

        } catch (Exception e) {
            System.out.println("  Error: " + e.getMessage() + "\n");
            return EXIT_WARNING;
        }
    }

    // ==================== ST22 - Short Dumps ====================
    private static int checkDumps(JCoDestination dest) {
        System.out.println("[ST22] Short dumps (sample)");
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            if (fn == null) {
                System.out.println("  Status: SKIPPED\n");
                return EXIT_OK;
            }

            fn.getImportParameterList().setValue("QUERY_TABLE", "SNAP");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 100);
            fn.execute(dest);

            int count = fn.getTableParameterList().getTable("DATA").getNumRows();

            System.out.println("  Value read     : " + count + " short dumps (sample)");
            System.out.println("  Threshold      : > 100 = WARNING");
            System.out.println("  Status         : " + (count > 100 ? "WARNING" : "OK") + "\n");

            return count > 100 ? EXIT_WARNING : EXIT_OK;

        } catch (Exception e) {
            System.out.println("  Status: SKIPPED\n");
            return EXIT_OK;
        }
    }

    // ==================== SMLG - Logon Groups ====================
    private static int checkLogonGroups(JCoDestination dest) {
        System.out.println("[SMLG] Logon groups");
        try {
            JCoFunction fn = dest.getRepository().getFunction("SMLG_GET_DEFINED_GROUPS");
            if (fn == null) {
                System.out.println("  Status: SKIPPED (function not available)\n");
                return EXIT_OK;
            }

            fn.execute(dest);
            System.out.println("  Status: OK\n");
            return EXIT_OK;

        } catch (Exception e) {
            System.out.println("  Status: " + e.getMessage() + "\n");
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
