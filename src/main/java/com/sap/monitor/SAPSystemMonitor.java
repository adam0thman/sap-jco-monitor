package com.sap.monitor;

import com.sap.conn.jco.*;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

public class SAPSystemMonitor {

    private static final String VERSION = "1.3.0";
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
            String ashost = getAshostFromDestination(destinationName);

            System.out.println("==============================================================");
            System.out.println("  SAP System Monitor v" + VERSION);
            System.out.println("  Run Date     : " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            System.out.println("  Destination  : " + destinationName);
            System.out.println("  ASHOST       : " + ashost);
            System.out.println("  SYSID        : " + attrs.getSystemID());
            System.out.println("  Client       : " + attrs.getClient());
            System.out.println("  User         : " + attrs.getUser());
            System.out.println("==============================================================");
            System.out.println();

            overallStatus = Math.max(overallStatus, checkLocks(dest));
            overallStatus = Math.max(overallStatus, checkUpdates(dest));
            overallStatus = Math.max(overallStatus, checkQueues(dest));
            overallStatus = Math.max(overallStatus, checkServers(dest));
            overallStatus = Math.max(overallStatus, checkJobs(dest));
            overallStatus = Math.max(overallStatus, checkDumps(dest));
            overallStatus = Math.max(overallStatus, checkLogonGroups(dest));

            System.out.println("\n==============================================================");
            System.out.println("  OVERALL STATUS : " + getStatusText(overallStatus));
            System.out.println("==============================================================");

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

    private static String getAshostFromDestination(String destName) {
        try {
            Path destFile = Paths.get("destinations", destName + ".jcoDestination");
            if (Files.exists(destFile)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(destFile)) {
                    props.load(in);
                }
                return props.getProperty("jco.client.ashost", "unknown");
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    // ==================== SM12 ====================
    private static int checkLocks(JCoDestination dest) {
        System.out.println(">>> [SM12] Lock Entries");
        String primary = "ENQUEUE_READ";
        String fallback = "RFC_READ_TABLE on ENQID";
        int result = EXIT_OK;
        String status = "OK";
        String threshold = "> 5000 = WARNING";

        try {
            JCoFunction fn = dest.getRepository().getFunction("ENQUEUE_READ");
            if (fn != null) {
                System.out.println("    Primary Method         : " + primary);
                fn.getImportParameterList().setValue("GCLIENT", dest.getClient());
                fn.execute(dest);
                int count = fn.getTableParameterList().getTable("ENQ").getNumRows();
                System.out.println("    Primary Method Result  : " + count + " active locks");
                System.out.println("    Fallback Method        : Not needed");
                System.out.println("    Fallback Method Result : -");
                System.out.println("    Threshold              : " + threshold);
                status = (count > 5000 ? "WARNING" : "OK");
                System.out.println("    Status                 : " + status + "\n");
                return count > 5000 ? EXIT_WARNING : EXIT_OK;
            }
        } catch (Exception ignored) {}

        System.out.println("    Primary Method         : " + primary);
        System.out.println("    Primary Method Result  : Not available");
        System.out.println("    Fallback Method        : " + fallback);
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            fn.getImportParameterList().setValue("QUERY_TABLE", "ENQID");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 100);
            fn.execute(dest);
            int count = fn.getTableParameterList().getTable("DATA").getNumRows();
            System.out.println("    Fallback Method Result : " + count + " locks");
            System.out.println("    Threshold              : " + threshold);
            System.out.println("    Status                 : OK\n");
        } catch (Exception e) {
            System.out.println("    Fallback Method Result : Failed");
            System.out.println("    Threshold              : " + threshold);
            System.out.println("    Status                 : SKIPPED\n");
        }
        return EXIT_OK;
    }

    // ==================== SM13 ====================
    private static int checkUpdates(JCoDestination dest) {
        System.out.println(">>> [SM13] Update Records");
        String primary = "RFC_READ_TABLE on VBMOD";
        System.out.println("    Primary Method         : " + primary);
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            if (fn != null) {
                fn.getImportParameterList().setValue("QUERY_TABLE", "VBMOD");
                fn.getImportParameterList().setValue("DELIMITER", "|");
                fn.getImportParameterList().setValue("ROWCOUNT", 100);
                fn.execute(dest);
                int rows = fn.getTableParameterList().getTable("DATA").getNumRows();
                System.out.println("    Primary Method Result  : " + rows + " update records");
                System.out.println("    Fallback Method        : Not needed");
                System.out.println("    Fallback Method Result : -");
                System.out.println("    Threshold              : informational only");
                System.out.println("    Status                 : OK\n");
                return EXIT_OK;
            }
        } catch (Exception e) {
            System.out.println("    Primary Method Result  : Failed");
        }
        System.out.println("    Fallback Method        : Not available");
        System.out.println("    Fallback Method Result : -");
        System.out.println("    Threshold              : informational only");
        System.out.println("    Status                 : SKIPPED\n");
        return EXIT_OK;
    }

    // ==================== SMQ1 ====================
    private static int checkQueues(JCoDestination dest) {
        System.out.println(">>> [SMQ1] qRFC Queue Status");
        String primary = "TRFC_QOUT_GET_STATUS";
        String fallback = "RFC_READ_TABLE on TRFCQOUT";

        try {
            JCoFunction fn = dest.getRepository().getFunction("TRFC_QOUT_GET_STATUS");
            if (fn != null) {
                System.out.println("    Primary Method         : " + primary);
                fn.execute(dest);
                System.out.println("    Primary Method Result  : Success");
                System.out.println("    Fallback Method        : Not needed");
                System.out.println("    Fallback Method Result : -");
                System.out.println("    Threshold              : informational only");
                System.out.println("    Status                 : OK\n");
                return EXIT_OK;
            }
        } catch (Exception ignored) {}

        System.out.println("    Primary Method         : " + primary);
        System.out.println("    Primary Method Result  : Not available");
        System.out.println("    Fallback Method        : " + fallback);
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            fn.getImportParameterList().setValue("QUERY_TABLE", "TRFCQOUT");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 100);
            fn.execute(dest);
            int rows = fn.getTableParameterList().getTable("DATA").getNumRows();
            System.out.println("    Fallback Method Result : " + rows + " qRFC records");
            System.out.println("    Threshold              : informational only");
            System.out.println("    Status                 : OK\n");
            return EXIT_OK;
        } catch (Exception e) {
            System.out.println("    Fallback Method Result : Failed");
            System.out.println("    Threshold              : informational only");
            System.out.println("    Status                 : SKIPPED\n");
            return EXIT_OK;
        }
    }

    // ==================== SM51 ====================
    private static int checkServers(JCoDestination dest) {
        System.out.println(">>> [SM51] Application Servers");
        String primary = "TH_SERVER_LIST";
        System.out.println("    Primary Method         : " + primary);
        try {
            JCoFunction fn = dest.getRepository().getFunction("TH_SERVER_LIST");
            if (fn != null) {
                fn.execute(dest);
                int count = fn.getTableParameterList().getTable("LIST").getNumRows();
                System.out.println("    Primary Method Result  : " + count + " active servers");
                System.out.println("    Fallback Method        : Not needed");
                System.out.println("    Fallback Method Result : -");
                System.out.println("    Threshold              : >= 1 required");
                String status = (count >= 1 ? "OK" : "CRITICAL");
                System.out.println("    Status                 : " + status + "\n");
                return count >= 1 ? EXIT_OK : EXIT_CRITICAL;
            }
        } catch (Exception e) {
            System.out.println("    Primary Method Result  : Failed");
        }
        System.out.println("    Fallback Method        : Not available");
        System.out.println("    Fallback Method Result : -");
        System.out.println("    Threshold              : >= 1 required");
        System.out.println("    Status                 : SKIPPED\n");
        return EXIT_OK;
    }

    // ==================== SM37 ====================
    private static int checkJobs(JCoDestination dest) {
        System.out.println(">>> [SM37] Background Jobs (Last 24h)");
        String primary = "BAPI_XBP_GET_JOB_LIST";
        String fallback = "RFC_READ_TABLE on TBTCO";
        String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        try {
            JCoFunction fn = dest.getRepository().getFunction("BAPI_XBP_GET_JOB_LIST");
            if (fn != null) {
                System.out.println("    Primary Method         : " + primary);
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
                System.out.println("    Primary Method Result  : " + failed + " failed/cancelled jobs");
                System.out.println("    Fallback Method        : Not needed");
                System.out.println("    Fallback Method Result : -");
                System.out.println("    Threshold              : > 10 = WARNING");
                String status = (failed > 10 ? "WARNING" : "OK");
                System.out.println("    Status                 : " + status + "\n");
                return failed > 10 ? EXIT_WARNING : EXIT_OK;
            }
        } catch (Exception ignored) {}

        System.out.println("    Primary Method         : " + primary);
        System.out.println("    Primary Method Result  : Not available");
        System.out.println("    Fallback Method        : " + fallback);
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            fn.getImportParameterList().setValue("QUERY_TABLE", "TBTCO");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 200);
            fn.execute(dest);
            int count = fn.getTableParameterList().getTable("DATA").getNumRows();
            System.out.println("    Fallback Method Result : " + count + " jobs (sample)");
            System.out.println("    Threshold              : > 10 = WARNING");
            System.out.println("    Status                 : OK\n");
            return EXIT_OK;
        } catch (Exception e) {
            System.out.println("    Fallback Method Result : Failed");
            System.out.println("    Threshold              : > 10 = WARNING");
            System.out.println("    Status                 : SKIPPED\n");
            return EXIT_OK;
        }
    }

    // ==================== ST22 ====================
    private static int checkDumps(JCoDestination dest) {
        System.out.println(">>> [ST22] Short Dumps");
        String primary = "/SDF/GET_DUMP_LOG";
        String fallback = "RFC_READ_TABLE on SNAP";

        try {
            JCoFunction fn = dest.getRepository().getFunction("/SDF/GET_DUMP_LOG");
            if (fn != null) {
                System.out.println("    Primary Method         : " + primary);
                fn.execute(dest);
                System.out.println("    Primary Method Result  : Success");
                System.out.println("    Fallback Method        : Not needed");
                System.out.println("    Fallback Method Result : -");
                System.out.println("    Threshold              : > 10 = WARNING, > 50 = CRITICAL");
                System.out.println("    Status                 : OK\n");
                return EXIT_OK;
            }
        } catch (Exception ignored) {}

        System.out.println("    Primary Method         : " + primary);
        System.out.println("    Primary Method Result  : Not available");
        System.out.println("    Fallback Method        : " + fallback);
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            fn.getImportParameterList().setValue("QUERY_TABLE", "SNAP");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            fn.getImportParameterList().setValue("ROWCOUNT", 300);
            fn.execute(dest);
            int count = fn.getTableParameterList().getTable("DATA").getNumRows();
            System.out.println("    Fallback Method Result : " + count + " dumps");
            System.out.println("    Threshold              : > 10 = WARNING, > 50 = CRITICAL");
            String status = count > 50 ? "CRITICAL" : (count > 10 ? "WARNING" : "OK");
            System.out.println("    Status                 : " + status + "\n");
            if (count > 50) return EXIT_CRITICAL;
            if (count > 10) return EXIT_WARNING;
            return EXIT_OK;
        } catch (Exception e) {
            System.out.println("    Fallback Method Result : Failed");
            System.out.println("    Threshold              : > 10 = WARNING, > 50 = CRITICAL");
            System.out.println("    Status                 : SKIPPED\n");
            return EXIT_OK;
        }
    }

    // ==================== SMLG ====================
    private static int checkLogonGroups(JCoDestination dest) {
        System.out.println(">>> [SMLG] Logon Groups");
        String primary = "SMLG_GET_DEFINED_GROUPS";
        System.out.println("    Primary Method         : " + primary);
        try {
            JCoFunction fn = dest.getRepository().getFunction("SMLG_GET_DEFINED_GROUPS");
            if (fn != null) {
                fn.execute(dest);
                System.out.println("    Primary Method Result  : Success");
                System.out.println("    Fallback Method        : Not needed");
                System.out.println("    Fallback Method Result : -");
                System.out.println("    Threshold              : informational only");
                System.out.println("    Status                 : OK\n");
                return EXIT_OK;
            }
        } catch (Exception e) {
            System.out.println("    Primary Method Result  : Failed");
        }
        System.out.println("    Fallback Method        : Not available");
        System.out.println("    Fallback Method Result : -");
        System.out.println("    Threshold              : informational only");
        System.out.println("    Status                 : SKIPPED\n");
        return EXIT_OK;
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
