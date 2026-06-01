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

            // === CONNECTION DETAILS (DEBUG) ===
            JCoAttributes attrs = dest.getAttributes();
            System.out.println("=== CONNECTION DETAILS ===");
            System.out.println("  Destination    : " + destinationName);
            System.out.println("  ASHOST         : " + attrs.getDestination());
            System.out.println("  SYSID          : " + attrs.getSystemID());
            System.out.println("  Client         : " + attrs.getClient());
            System.out.println("  User           : " + attrs.getUser());
            System.out.println("  Language       : " + attrs.getLanguage());
            System.out.println("  Host           : " + attrs.getHost());
            System.out.println("  System Number  : " + attrs.getSystemNumber());
            System.out.println("==========================\n");

            overallStatus = Math.max(overallStatus, checkLocks(dest));
            overallStatus = Math.max(overallStatus, checkUpdates(dest));
            overallStatus = Math.max(overallStatus, checkQueues(dest));
            overallStatus = Math.max(overallStatus, checkServers(dest));
            overallStatus = Math.max(overallStatus, checkJobs(dest));
            overallStatus = Math.max(overallStatus, checkDumps(dest));
            overallStatus = Math.max(overallStatus, checkLogonGroups(dest));

            System.out.println("\n=== OVERALL STATUS: " + getStatusText(overallStatus) + " ===");

        } catch (Exception e) {
            System.err.println("FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
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

    // ==================== SM12 ====================
    private static int checkLocks(JCoDestination dest) {
        System.out.println(">>> [SM12] Checking lock entries (ENQUEUE_READ)");
        try {
            JCoFunction fn = dest.getRepository().getFunction("ENQUEUE_READ");
            if (fn == null) {
                System.out.println("    RESULT: SKIPPED - Function ENQUEUE_READ not found\n");
                return EXIT_OK;
            }

            fn.getImportParameterList().setValue("GCLIENT", dest.getClient());
            System.out.println("    Calling ENQUEUE_READ with GCLIENT=" + dest.getClient());

            fn.execute(dest);

            JCoTable lockTable = fn.getTableParameterList().getTable("ENQ");
            int count = lockTable.getNumRows();

            System.out.println("    RETURNED     : " + count + " locks");
            System.out.println("    THRESHOLD    : > 5000 = WARNING");
            System.out.println("    RESULT       : " + (count > 5000 ? "WARNING" : "OK") + "\n");

            return count > 5000 ? EXIT_WARNING : EXIT_OK;

        } catch (Exception e) {
            System.out.println("    ERROR: " + e.getMessage() + "\n");
            return EXIT_WARNING;
        }
    }

    // ==================== SM13 ====================
    private static int checkUpdates(JCoDestination dest) {
        System.out.println(">>> [SM13] Checking update records (RFC_READ_TABLE on VBMOD)");
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            if (fn == null) {
                System.out.println("    RESULT: SKIPPED\n");
                return EXIT_OK;
            }

            fn.getImportParameterList().setValue("QUERY_TABLE", "VBMOD");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 100);
            System.out.println("    Calling RFC_READ_TABLE on VBMOD (ROWCOUNT=100)");

            fn.execute(dest);

            int rows = fn.getTableParameterList().getTable("DATA").getNumRows();
            System.out.println("    RETURNED     : " + rows + " update records");
            System.out.println("    THRESHOLD    : informational only");
            System.out.println("    RESULT       : OK\n");

            return EXIT_OK;

        } catch (Exception e) {
            System.out.println("    SKIPPED: " + e.getMessage() + "\n");
            return EXIT_OK;
        }
    }

    // ==================== SMQ1 ====================
    private static int checkQueues(JCoDestination dest) {
        System.out.println(">>> [SMQ1] Checking qRFC queues (TRFC_QOUT_GET_QUEUES)");
        try {
            JCoFunction fn = dest.getRepository().getFunction("TRFC_QOUT_GET_QUEUES");
            if (fn == null) {
                System.out.println("    RESULT: SKIPPED - Function not available on this system\n");
                return EXIT_OK;
            }

            System.out.println("    Calling TRFC_QOUT_GET_QUEUES...");
            fn.execute(dest);
            System.out.println("    RESULT: OK\n");
            return EXIT_OK;

        } catch (Exception e) {
            System.out.println("    RESULT: WARNING - " + e.getMessage() + "\n");
            return EXIT_WARNING;
        }
    }

    // ==================== SM51 ====================
    private static int checkServers(JCoDestination dest) {
        System.out.println(">>> [SM51] Checking application servers (TH_SERVER_LIST)");
        try {
            JCoFunction fn = dest.getRepository().getFunction("TH_SERVER_LIST");
            if (fn == null) {
                System.out.println("    RESULT: SKIPPED\n");
                return EXIT_OK;
            }

            System.out.println("    Calling TH_SERVER_LIST...");
            fn.execute(dest);

            JCoTable servers = fn.getTableParameterList().getTable("LIST");
            int count = servers.getNumRows();

            System.out.println("    RETURNED     : " + count + " active application servers");
            System.out.println("    THRESHOLD    : >= 1 required");
            System.out.println("    RESULT       : " + (count >= 1 ? "OK" : "CRITICAL") + "\n");

            return count >= 1 ? EXIT_OK : EXIT_CRITICAL;

        } catch (Exception e) {
            System.out.println("    ERROR: " + e.getMessage() + "\n");
            return EXIT_WARNING;
        }
    }

    // ==================== SM37 ====================
    private static int checkJobs(JCoDestination dest) {
        System.out.println(">>> [SM37] Checking background jobs (BAPI_XBP_GET_JOB_LIST)");
        try {
            JCoFunction fn = dest.getRepository().getFunction("BAPI_XBP_GET_JOB_LIST");
            if (fn == null) {
                System.out.println("    RESULT: SKIPPED - BAPI not available\n");
                return EXIT_OK;
            }

            String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            fn.getImportParameterList().setValue("JOBNAME", "*");
            fn.getImportParameterList().setValue("USERNAME", "*");
            fn.getImportParameterList().setValue("FROM_DATE", yesterday);
            fn.getImportParameterList().setValue("TO_DATE", today);

            System.out.println("    Calling BAPI_XBP_GET_JOB_LIST");
            System.out.println("      Parameters: JOBNAME=*, USERNAME=*, FROM_DATE=" + yesterday + ", TO_DATE=" + today);

            fn.execute(dest);

            JCoTable jobs = fn.getTableParameterList().getTable("JOBLIST");
            int failed = 0;

            for (int i = 0; i < jobs.getNumRows(); i++) {
                jobs.setRow(i);
                String status = jobs.getString("STATUS");
                if ("A".equals(status) || "C".equals(status)) failed++;
            }

            System.out.println("    RETURNED     : " + failed + " failed/cancelled jobs (last 24h)");
            System.out.println("    THRESHOLD    : > 10 = WARNING");
            System.out.println("    RESULT       : " + (failed > 10 ? "WARNING" : "OK") + "\n");

            return failed > 10 ? EXIT_WARNING : EXIT_OK;

        } catch (Exception e) {
            System.out.println("    ERROR: " + e.getMessage() + "\n");
            return EXIT_WARNING;
        }
    }

    // ==================== ST22 ====================
    private static int checkDumps(JCoDestination dest) {
        System.out.println(">>> [ST22] Checking short dumps (RFC_READ_TABLE on SNAP)");
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            if (fn == null) {
                System.out.println("    RESULT: SKIPPED\n");
                return EXIT_OK;
            }

            fn.getImportParameterList().setValue("QUERY_TABLE", "SNAP");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 200);

            System.out.println("    Calling RFC_READ_TABLE on SNAP (ROWCOUNT=200)");
            fn.execute(dest);

            int count = fn.getTableParameterList().getTable("DATA").getNumRows();

            System.out.println("    RETURNED     : " + count + " short dumps (sample)");
            System.out.println("    THRESHOLD    : > 100 = WARNING");
            System.out.println("    RESULT       : " + (count > 100 ? "WARNING" : "OK") + "\n");

            return count > 100 ? EXIT_WARNING : EXIT_OK;

        } catch (Exception e) {
            System.out.println("    SKIPPED\n");
            return EXIT_OK;
        }
    }

    // ==================== SMLG ====================
    private static int checkLogonGroups(JCoDestination dest) {
        System.out.println(">>> [SMLG] Checking logon groups (SMLG_GET_DEFINED_GROUPS)");
        try {
            JCoFunction fn = dest.getRepository().getFunction("SMLG_GET_DEFINED_GROUPS");
            if (fn == null) {
                System.out.println("    RESULT: SKIPPED - Function not available\n");
                return EXIT_OK;
            }

            System.out.println("    Calling SMLG_GET_DEFINED_GROUPS...");
            fn.execute(dest);
            System.out.println("    RESULT: OK\n");
            return EXIT_OK;

        } catch (Exception e) {
            System.out.println("    RESULT: " + e.getMessage() + "\n");
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
