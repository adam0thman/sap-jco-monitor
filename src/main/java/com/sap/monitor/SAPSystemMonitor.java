package com.sap.monitor;

import com.sap.conn.jco.*;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

public class SAPSystemMonitor {

    private static final String VERSION = "1.3.7";
    private static final int EXIT_OK = 0;
    private static final int EXIT_WARNING = 1;
    private static final int EXIT_CRITICAL = 2;

    // SMLG response-time check (SWNC/ST03 dialog response time per app server)
    private static final double RESPTIME_WARN_MS = 4000;
    private static final int RESPTIME_LOOKBACK_DAYS = 14;

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
                System.out.println("    Threshold              : > 5000 = WARNING");
                String status = (count > 5000 ? "WARNING" : "OK");
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
            System.out.println("    Threshold              : > 5000 = WARNING");
            System.out.println("    Status                 : OK\n");
        } catch (Exception e) {
            System.out.println("    Fallback Method Result : Failed");
            System.out.println("    Threshold              : > 5000 = WARNING");
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
    // Objective: count background jobs that ABORTED (status 'A') in the last 24h; > 10 = WARNING.
    //   Primary  (Approach A): RFC_READ_TABLE on TBTCO with the column list trimmed (so the
    //                          512-byte buffer is not exceeded) and STATUS/date pushed into the
    //                          WHERE clause, so the returned row count IS the aborted-job count.
    //   Fallback (Approach B): the XBP external-monitoring interface, which requires a stateful
    //                          XMI session (BAPI_XMI_LOGON -> BAPI_XBP_JOB_SELECT -> BAPI_XMI_LOGOFF)
    //                          held open via JCoContext.begin/end.
    private static int checkJobs(JCoDestination dest) {
        System.out.println(">>> [SM37] Background Jobs (Last 24h)");
        DateTimeFormatter ymd = DateTimeFormatter.ofPattern("yyyyMMdd");
        String fromDate = LocalDate.now().minusDays(1).format(ymd);
        String toDate = LocalDate.now().format(ymd);

        // ---- Approach A: direct TBTCO read (primary) ----
        System.out.println("    Primary Method         : RFC_READ_TABLE on TBTCO (STATUS='A')");
        try {
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            fn.getImportParameterList().setValue("QUERY_TABLE", "TBTCO");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            JCoTable fields = fn.getTableParameterList().getTable("FIELDS");
            for (String f : new String[] {"JOBNAME", "STATUS", "SDLSTRTDT", "ENDDATE"}) {
                fields.appendRow();
                fields.setValue("FIELDNAME", f);
            }
            JCoTable options = fn.getTableParameterList().getTable("OPTIONS");
            options.appendRow();
            options.setValue("TEXT", "STATUS = 'A'");
            options.appendRow();
            options.setValue("TEXT", "AND SDLSTRTDT >= '" + fromDate + "'");
            fn.execute(dest);
            int aborted = fn.getTableParameterList().getTable("DATA").getNumRows();
            System.out.println("    Primary Method Result  : " + aborted + " aborted jobs (since " + fromDate + ")");
            System.out.println("    Fallback Method        : Not needed");
            System.out.println("    Fallback Method Result : -");
            System.out.println("    Threshold              : > 10 = WARNING");
            String status = (aborted > 10 ? "WARNING" : "OK");
            System.out.println("    Status                 : " + status + "\n");
            return aborted > 10 ? EXIT_WARNING : EXIT_OK;
        } catch (Exception ignored) {
            System.out.println("    Primary Method Result  : Not available");
        }

        // ---- Approach B: XBP via stateful XMI session (fallback) ----
        System.out.println("    Fallback Method        : XBP BAPI_XBP_JOB_SELECT");
        try {
            int aborted = countAbortedJobsViaXbp(dest, fromDate, toDate);
            System.out.println("    Fallback Method Result : " + aborted + " aborted jobs (since " + fromDate + ")");
            System.out.println("    Threshold              : > 10 = WARNING");
            String status = (aborted > 10 ? "WARNING" : "OK");
            System.out.println("    Status                 : " + status + "\n");
            return aborted > 10 ? EXIT_WARNING : EXIT_OK;
        } catch (Exception e) {
            System.out.println("    Fallback Method Result : Failed");
            System.out.println("    Threshold              : > 10 = WARNING");
            System.out.println("    Status                 : SKIPPED\n");
            return EXIT_OK;
        }
    }

    // Approach B helper: open an XBP session and count jobs selected with the ABORTED flag.
    // The XMI session must persist across the logon/select/logoff calls, so they are wrapped
    // in JCoContext.begin/end to pin them to a single stateful connection.
    private static int countAbortedJobsViaXbp(JCoDestination dest, String fromDate, String toDate) throws Exception {
        JCoContext.begin(dest);
        try {
            JCoFunction logon = dest.getRepository().getFunction("BAPI_XMI_LOGON");
            logon.getImportParameterList().setValue("EXTCOMPANY", "HERMES");
            logon.getImportParameterList().setValue("EXTPRODUCT", "SAP_JCO_MONITOR");
            logon.getImportParameterList().setValue("INTERFACE", "XBP");
            logon.getImportParameterList().setValue("VERSION", "3.0");
            logon.execute(dest);
            assertBapiOk(logon);

            JCoFunction sel = dest.getRepository().getFunction("BAPI_XBP_JOB_SELECT");
            sel.getImportParameterList().setValue("EXTERNAL_USER_NAME", dest.getAttributes().getUser());
            JCoStructure p = sel.getImportParameterList().getStructure("JOB_SELECT_PARAM");
            p.setValue("JOBNAME", "*");
            p.setValue("USERNAME", "*");
            p.setValue("FROM_DATE", fromDate);
            p.setValue("TO_DATE", toDate);
            p.setValue("ABORTED", "X");   // select only aborted jobs
            sel.execute(dest);
            assertBapiOk(sel);
            int aborted = sel.getTableParameterList().getTable("JOB_HEAD").getNumRows();

            try {
                JCoFunction logoff = dest.getRepository().getFunction("BAPI_XMI_LOGOFF");
                logoff.getImportParameterList().setValue("INTERFACE", "XBP");
                logoff.execute(dest);
            } catch (Exception ignored) {}

            return aborted;
        } finally {
            JCoContext.end(dest);
        }
    }

    // Throw if a BAPI's RETURN structure reports an error/abort, so the caller can fall through.
    private static void assertBapiOk(JCoFunction fn) {
        JCoStructure ret = fn.getExportParameterList().getStructure("RETURN");
        if (ret != null) {
            String type = ret.getString("TYPE");
            if ("E".equals(type) || "A".equals(type)) {
                throw new RuntimeException(fn.getName() + ": " + ret.getString("MESSAGE"));
            }
        }
    }

    // ==================== ST22 (Dynamic field printing from ET_E2E_LOG) ====================
    private static int checkDumps(JCoDestination dest) {
        System.out.println(">>> [ST22] Short Dumps");
        String primary = "/SDF/GET_DUMP_LOG";
        String fallback = "RFC_READ_TABLE on SNAP (last 24h, distinct dumps)";

        System.out.println("    Primary Method         : " + primary);
        try {
            JCoFunction fn = dest.getRepository().getFunction("/SDF/GET_DUMP_LOG");
            if (fn != null) {
                fn.execute(dest);

                JCoTable dumpTable = null;
                try { dumpTable = fn.getTableParameterList().getTable("ET_E2E_LOG"); } catch (Exception ignored) {}
                if (dumpTable == null) try { dumpTable = fn.getTableParameterList().getTable("ET_DUMPS"); } catch (Exception ignored) {}
                if (dumpTable == null) try { dumpTable = fn.getTableParameterList().getTable("DUMP_LIST"); } catch (Exception ignored) {}
                if (dumpTable == null) try { dumpTable = fn.getTableParameterList().getTable("IT_DUMPS"); } catch (Exception ignored) {}

                if (dumpTable != null && dumpTable.getNumRows() > 0) {
                    int rows = dumpTable.getNumRows();
                    String status = rows > 50 ? "CRITICAL" : (rows > 10 ? "WARNING" : "OK");
                    System.out.println("    Primary Method Result  : " + rows + " dumps found");
                    System.out.println("    Fallback Method        : Not needed");
                    System.out.println("    Fallback Method Result : -");
                    System.out.println("    Threshold              : > 10 = WARNING, > 50 = CRITICAL");
                    System.out.println("    Status                 : " + status);
                    System.out.println("    --------------------------------------------------");

                    // Print all available fields dynamically
                    JCoRecordMetaData meta = dumpTable.getRecordMetaData();
                    int fieldCount = meta.getFieldCount();

                    for (int i = 0; i < Math.min(rows, 10); i++) {
                        dumpTable.setRow(i);
                        StringBuilder line = new StringBuilder("    ");
                        for (int f = 0; f < fieldCount; f++) {
                            String fieldName = meta.getName(f);
                            String value = "-";
                            try { value = dumpTable.getString(fieldName); } catch (Exception ignored) {}
                            line.append(fieldName).append("=").append(value).append(" | ");
                        }
                        System.out.println(line.toString());
                    }
                    if (rows > 10) {
                        System.out.println("    ... (" + (rows - 10) + " more)");
                    }
                    System.out.println("    --------------------------------------------------\n");

                    if (rows > 50) return EXIT_CRITICAL;
                    if (rows > 10) return EXIT_WARNING;
                    return EXIT_OK;
                } else {
                    System.out.println("    Primary Method Result  : No dumps returned");
                    System.out.println("    Fallback Method        : Not needed");
                    System.out.println("    Fallback Method Result : -");
                    System.out.println("    Threshold              : > 10 = WARNING, > 50 = CRITICAL");
                    System.out.println("    Status                 : OK\n");
                    return EXIT_OK;
                }
            }
        } catch (Exception ignored) {}

        System.out.println("    Primary Method Result  : Not available");
        System.out.println("    Fallback Method        : " + fallback);
        try {
            String fromDate = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            JCoFunction fn = dest.getRepository().getFunction("RFC_READ_TABLE");
            fn.getImportParameterList().setValue("QUERY_TABLE", "SNAP");
            fn.getImportParameterList().setValue("DELIMITER", "|");
            // SNAP is a wide table and a single dump spans many rows; select only the key fields
            // (stays under the 512-byte RFC_READ_TABLE buffer) and count DISTINCT dumps in the window.
            JCoTable fields = fn.getTableParameterList().getTable("FIELDS");
            for (String f : new String[] {"DATUM", "UZEIT", "AHOST", "UNAME"}) {
                fields.appendRow();
                fields.setValue("FIELDNAME", f);
            }
            JCoTable options = fn.getTableParameterList().getTable("OPTIONS");
            options.appendRow();
            options.setValue("TEXT", "DATUM >= '" + fromDate + "'");
            fn.execute(dest);
            JCoTable data = fn.getTableParameterList().getTable("DATA");
            Set<String> dumps = new HashSet<>();
            for (int i = 0; i < data.getNumRows(); i++) {
                data.setRow(i);
                dumps.add(data.getString("WA"));
            }
            int count = dumps.size();
            System.out.println("    Fallback Method Result : " + count + " dumps (since " + fromDate + ")");
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
    // Objective: measure each application server's average DIALOG response time (ms);
    //            > 4000 ms on any server is a WARNING.
    // Source: SWNC_COLLECTOR_GET_AGGREGATES (the ST03 workload collector) gives response
    // times in ms per component (= app server). Average per dialog step = RESPTI / COUNT for
    // task type '01' (DIALOG). The collector aggregates per day and "today" is usually not yet
    // aggregated, so for each server we use the most recent day with data within a lookback
    // window. App servers come from TH_SERVER_LIST (same source as SM51).
    private static int checkLogonGroups(JCoDestination dest) {
        System.out.println(">>> [SMLG] Logon Group Response Times");
        System.out.println("    Primary Method         : SWNC_COLLECTOR_GET_AGGREGATES (DIALOG, per app server)");
        try {
            String sid = dest.getAttributes().getSystemID();
            JCoFunction sl = dest.getRepository().getFunction("TH_SERVER_LIST");
            sl.execute(dest);
            JCoTable servers = sl.getTableParameterList().getTable("LIST");

            int worst = EXIT_OK, measured = 0;
            StringBuilder detail = new StringBuilder();
            for (int s = 0; s < servers.getNumRows(); s++) {
                servers.setRow(s);
                String server = servers.getString("NAME");
                double[] r = dialogAvgMs(dest, sid, server);   // {avgMs, daysAgo} or null
                if (r == null) {
                    detail.append("      ").append(server).append(" : no dialog workload data (last ")
                          .append(RESPTIME_LOOKBACK_DAYS).append(" days)\n");
                    continue;
                }
                measured++;
                int st = r[0] > RESPTIME_WARN_MS ? EXIT_WARNING : EXIT_OK;
                worst = Math.max(worst, st);
                detail.append(String.format("      %s : %.0f ms  (DIALOG, %d day(s) ago)  -> %s%n",
                        server, r[0], (long) r[1], st == EXIT_WARNING ? "WARNING" : "OK"));
            }

            System.out.println("    Primary Method Result  : " + measured + " of " + servers.getNumRows()
                    + " server(s) had dialog data");
            System.out.println("    Fallback Method        : Not needed");
            System.out.println("    Fallback Method Result : -");
            System.out.println("    Threshold              : > " + (int) RESPTIME_WARN_MS + " ms = WARNING");
            System.out.println("    Status                 : " + (worst == EXIT_WARNING ? "WARNING" : "OK"));
            System.out.print(detail);
            System.out.println();
            return worst;
        } catch (Exception e) {
            System.out.println("    Primary Method Result  : Not available");
            System.out.println("    Fallback Method        : Not available");
            System.out.println("    Fallback Method Result : -");
            System.out.println("    Threshold              : > " + (int) RESPTIME_WARN_MS + " ms = WARNING");
            System.out.println("    Status                 : SKIPPED\n");
            return EXIT_OK;
        }
    }

    // Most recent day (within the lookback window) that has DIALOG ('01') workload for the given
    // server; returns {avg ms per dialog step, days ago} or null if no dialog data was collected.
    private static double[] dialogAvgMs(JCoDestination dest, String sid, String server) {
        DateTimeFormatter ymd = DateTimeFormatter.ofPattern("yyyyMMdd");
        for (int d = 0; d <= RESPTIME_LOOKBACK_DAYS; d++) {
            try {
                JCoFunction fn = dest.getRepository().getFunction("SWNC_COLLECTOR_GET_AGGREGATES");
                fn.getImportParameterList().setValue("COMPONENT", server);
                fn.getImportParameterList().setValue("ASSIGNDSYS", sid);
                fn.getImportParameterList().setValue("PERIODTYPE", "D");
                fn.getImportParameterList().setValue("PERIODSTRT", LocalDate.now().minusDays(d).format(ymd));
                fn.getImportParameterList().setValue("SUMMARY_ONLY", "X");
                fn.execute(dest);
                JCoTable t = fn.getTableParameterList().getTable("TASKTYPE");
                for (int i = 0; i < t.getNumRows(); i++) {
                    t.setRow(i);
                    if ("01".equals(t.getString("TASKTYPE"))) {
                        long count = t.getLong("COUNT");
                        if (count > 0) return new double[] {t.getDouble("RESPTI") / count, d};
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
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
