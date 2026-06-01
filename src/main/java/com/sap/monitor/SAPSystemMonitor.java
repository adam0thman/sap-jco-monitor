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
        System.out.println(">>> [SM12] Checking lock entries");
        try {
            JCoFunction fn = dest.getRepository().getFunction("ENQUEUE_READ");
            if (fn != null) {
                System.out.println("    Primary: ENQUEUE_READ");
                fn.getImportParameterList().setValue("GCLIENT", dest.getClient());
                fn.execute(dest);
                int count = fn.getTableParameterList().getTable("ENQ").getNumRows();
                System.out.println("    RETURNED     : " + count + " locks");
                System.out.println("    THRESHOLD    : > 5000 = WARNING");
                System.out.println("    RESULT       : " + (count > 5000 ? "WARNING" : "OK") + "\n");
                return count > 5000 ? EXIT_WARNING : EXIT_OK;
            } else {
                System.out.println("    Primary not available. Falling back to RFC_READ_TABLE on ENQID...");
            }
        } catch (Exception e) {
            System.out.println("    Primary failed: " + e.getMessage() + ". Trying fallback...");
        }

        // Fallback
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            fn.getImportParameterList().setValue("QUERY_TABLE", "ENQID");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 100);
            fn.execute(dest);
            int count = fn.getTableParameterList().getTable("DATA").getNumRows();
            System.out.println("    FALLBACK RETURNED : " + count + " locks (ENQID)");
            System.out.println("    RESULT            : OK\n");
            return EXIT_OK;
        } catch (Exception e) {
            System.out.println("    Fallback also failed. RESULT: SKIPPED\n");
            return EXIT_OK;
        }
    }

    // ==================== SM13 ====================
    private static int checkUpdates(JCoDestination dest) {
        System.out.println(">>> [SM13] Checking update records");
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            if (fn == null) {
                System.out.println("    RESULT: SKIPPED\n");
                return EXIT_OK;
            }
            fn.getImportParameterList().setValue("QUERY_TABLE", "VBMOD");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 100);
            System.out.println("    Using RFC_READ_TABLE on VBMOD");
            fn.execute(dest);
            int rows = fn.getTableParameterList().getTable("DATA").getNumRows();
            System.out.println("    RETURNED     : " + rows + " update records");
            System.out.println("    RESULT       : OK\n");
            return EXIT_OK;
        } catch (Exception e) {
            System.out.println("    RESULT: SKIPPED\n");
            return EXIT_OK;
        }
    }

    // ==================== SMQ1 ====================
    private static int checkQueues(JCoDestination dest) {
        System.out.println(">>> [SMQ1] Checking qRFC queues");

        // Try TRFC_QOUT_GET_STATUS first
        try {
            JCoFunction fn = dest.getRepository().getFunction("TRFC_QOUT_GET_STATUS");
            if (fn != null) {
                System.out.println("    Primary: TRFC_QOUT_GET_STATUS");
                fn.execute(dest);
                System.out.println("    RESULT: OK\n");
                return EXIT_OK;
            }
        } catch (Exception ignored) {}

        System.out.println("    Primary not available. Falling back to RFC_READ_TABLE on TRFCQOUT...");

        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            fn.getImportParameterList().setValue("QUERY_TABLE", "TRFCQOUT");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 100);
            fn.execute(dest);
            int rows = fn.getTableParameterList().getTable("DATA").getNumRows();
            System.out.println("    FALLBACK RETURNED : " + rows + " qRFC records");
            System.out.println("    RESULT            : OK\n");
            return EXIT_OK;
        } catch (Exception e) {
            System.out.println("    Fallback failed. RESULT: SKIPPED\n");
            return EXIT_OK;
        }
    }

    // ==================== SM51 ====================
    private static int checkServers(JCoDestination dest) {
        System.out.println(">>> [SM51] Checking application servers");
        try {
            JCoFunction fn = dest.getRepository().getFunction("TH_SERVER_LIST");
            if (fn == null) {
                System.out.println("    RESULT: SKIPPED\n");
                return EXIT_OK;
            }
            System.out.println("    Using TH_SERVER_LIST");
            fn.execute(dest);
            int count = fn.getTableParameterList().getTable("LIST").getNumRows();
            System.out.println("    RETURNED     : " + count + " servers");
            System.out.println("    RESULT       : " + (count >= 1 ? "OK" : "CRITICAL") + "\n");
            return count >= 1 ? EXIT_OK : EXIT_CRITICAL;
        } catch (Exception e) {
            System.out.println("    ERROR: " + e.getMessage() + "\n");
            return EXIT_WARNING;
        }
    }

    // ==================== SM37 (with fallback) ====================
    private static int checkJobs(JCoDestination dest) {
        System.out.println(">>> [SM37] Checking background jobs");

        // Try BAPI first
        try {
            JCoFunction fn = dest.getRepository().getFunction("BAPI_XBP_GET_JOB_LIST");
            if (fn != null) {
                System.out.println("    Primary: BAPI_XBP_GET_JOB_LIST");
                String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                fn.getImportParameterList().setValue("JOBNAME", "*");
                fn.getImportParameterList().setValue("USERNAME", "*");
                fn.getImportParameterList().setValue("FROM_DATE", yesterday);
                fn.execute(dest);
                int failed = 0;
                JCoTable jobs = fn.getTableParameterList().getTable("JOBLIST");
                for (int i = 0; i < jobs.getNumRows(); i++) {
                    jobs.setRow(i);
                    String status = jobs.getString("STATUS");
                    if ("A".equals(status) || "C".equals(status)) failed++;
                }
                System.out.println("    RETURNED     : " + failed + " failed jobs");
                System.out.println("    THRESHOLD    : > 10 = WARNING");
                System.out.println("    RESULT       : " + (failed > 10 ? "WARNING" : "OK") + "\n");
                return failed > 10 ? EXIT_WARNING : EXIT_OK;
            }
        } catch (Exception ignored) {}

        System.out.println("    Primary not available. Falling back to RFC_READ_TABLE on TBTCO...");

        // Fallback to TBTCO
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            fn.getImportParameterList().setValue("QUERY_TABLE", "TBTCO");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 200);
            fn.execute(dest);
            int failed = fn.getTableParameterList().getTable("DATA").getNumRows();
            System.out.println("    FALLBACK RETURNED : " + failed + " jobs (TBTCO sample)");
            System.out.println("    RESULT            : OK\n");
            return EXIT_OK;
        } catch (Exception e) {
            System.out.println("    Fallback failed. RESULT: SKIPPED\n");
            return EXIT_OK;
        }
    }

    // ==================== ST22 ====================
    private static int checkDumps(JCoDestination dest) {
        System.out.println(">>> [ST22] Checking short dumps");
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            if (fn == null) {
                System.out.println("    RESULT: SKIPPED\n");
                return EXIT_OK;
            }
            fn.getImportParameterList().setValue("QUERY_TABLE", "SNAP");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 300);
            System.out.println("    Using RFC_READ_TABLE on SNAP");
            fn.execute(dest);
            int count = fn.getTableParameterList().getTable("DATA").getNumRows();
            System.out.println("    RETURNED     : " + count + " dumps");
            System.out.println("    THRESHOLD    : > 10 = WARNING, > 50 = CRITICAL");
            String result = count > 50 ? "CRITICAL" : (count > 10 ? "WARNING" : "OK");
            System.out.println("    RESULT       : " + result + "\n");
            if (count > 50) return EXIT_CRITICAL;
            if (count > 10) return EXIT_WARNING;
            return EXIT_OK;
        } catch (Exception e) {
            System.out.println("    RESULT: SKIPPED\n");
            return EXIT_OK;
        }
    }

    // ==================== SMLG ====================
    private static int checkLogonGroups(JCoDestination dest) {
        System.out.println(">>> [SMLG] Checking logon groups");
        try {
            JCoFunction fn = dest.getRepository().getFunction("SMLG_GET_DEFINED_GROUPS");
            if (fn == null) {
                System.out.println("    RESULT: SKIPPED\n");
                return EXIT_OK;
            }
            System.out.println("    Using SMLG_GET_DEFINED_GROUPS");
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
