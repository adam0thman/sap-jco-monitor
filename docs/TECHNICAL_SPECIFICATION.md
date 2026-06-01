# SAP JCo Monitor - Technical Specification

**Version:** 1.3.7  
**Date:** 2026-06-01  
**Author:** Hermes Agent

---

## 1. Overall Program Architecture

### 1.1 Design Goals
- Pure Java implementation using SAP Java Connector (JCo) 3.1
- Cron-friendly with clear exit codes (0=OK, 1=Warning, 2=Critical)
- Standardized output format across all checks
- Graceful degradation with clear primary vs fallback messaging
- Dynamic field printing for complex RFC results (e.g. ST22)

### 1.2 Standardized Check Output Format

Every monitoring check follows this consistent structure:

```
>>> [XXX] Check Name
    Primary Method         : <Primary BAPI/RFC>
    Primary Method Result  : <Actual result or "Not available">
    Fallback Method        : <Fallback method or "Not needed">
    Fallback Method Result : <Result or "-">
    Threshold              : <Threshold description>
    Status                 : <OK / WARNING / CRITICAL / SKIPPED>
```

---

## 2. Monitoring Checks - Final Implementation (v1.3.7)

### 2.1 SM12 - Lock Entries
**Primary:** `ENQUEUE_READ`  
**Fallback:** `RFC_READ_TABLE` on `ENQID`  
**Threshold:** > 5000 = WARNING

### 2.2 SM13 - Update Records
**Primary:** `RFC_READ_TABLE` on `VBMOD`  
**Threshold:** Informational only

### 2.3 SMQ1 - qRFC Queue Status
**Primary:** `TRFC_QOUT_GET_STATUS`  
**Fallback:** `RFC_READ_TABLE` on `TRFCQOUT`  
**Threshold:** Informational only

### 2.4 SM51 - Application Servers
**Primary:** `TH_SERVER_LIST`  
**Threshold:** >= 1 required

### 2.5 SM37 - Background Jobs (Last 24h)
**Objective:** count jobs that aborted (status `A`) since yesterday.
**Primary (A):** `RFC_READ_TABLE` on `TBTCO`, columns trimmed to `JOBNAME, STATUS, SDLSTRTDT, ENDDATE` (avoids the 512-byte buffer limit) with WHERE `STATUS = 'A' AND SDLSTRTDT >= <yesterday>`; the returned row count is the aborted-job count.
**Fallback (B):** XBP external-monitoring interface over a stateful XMI session — `BAPI_XMI_LOGON` (INTERFACE `XBP`, VERSION `3.0`) → `BAPI_XBP_JOB_SELECT` with `JOB_SELECT_PARAM-ABORTED = 'X'` → `BAPI_XMI_LOGOFF`, wrapped in `JCoContext.begin/end`.
**Threshold:** > 10 = WARNING

**Note:** TBTCO status values are R=running, Y=ready, P=scheduled, S=released, A=aborted, F=finished, Z=active. Only `A` counts as a failure. The deprecated `BAPI_XBP_GET_JOB_LIST` / non-RFC `BP_JOB_SELECT` were removed; the former is not even present on some releases (e.g. the tested S/4HANA system).

### 2.6 ST22 - Short Dumps
**Primary:** `/SDF/GET_DUMP_LOG` (reads from `ET_E2E_LOG`)  
**Fallback:** `RFC_READ_TABLE` on `SNAP`  
**Thresholds:** > 10 = WARNING, > 50 = CRITICAL

**Note:** The script dynamically prints all available fields from `ET_E2E_LOG`.

### 2.7 SMLG - Logon Group Response Times
**Objective:** measure each application server's average DIALOG response time (ms).
**Method:** `SWNC_COLLECTOR_GET_AGGREGATES` (the ST03 workload collector) returns response times in ms per component (= app server). Average per dialog step = `RESPTI / COUNT` for task type `'01'` (DIALOG). App servers are enumerated via `TH_SERVER_LIST`. The collector aggregates per day and the current day is usually not yet aggregated, so for each server the most recent day with data within a 14-day lookback is used.
**Threshold:** > 4000 ms on any server = WARNING.

**Note:** This is the ST03 aggregated dialog response time (the standard SAP response-time metric), not a real-time sample — true real-time per-server load-distribution figures from the message server are not reliably exposed over RFC on the tested release.

---

## 3. Version History

| Version | Changes |
|---------|---------|
| 1.3.7   | SMLG implemented as per-app-server DIALOG response-time check via `SWNC_COLLECTOR_GET_AGGREGATES` (ST03), avg ms = `RESPTI/COUNT` for task type `'01'`, most-recent-day-with-data over a 14-day lookback, servers from `TH_SERVER_LIST`; > 4000 ms = WARNING. Replaces the prior informational logon-group listing. ST22 fallback fixed: single `Primary Method` header (no duplicate when `/SDF/GET_DUMP_LOG` exists but errors) and the `SNAP` read now selects only key fields with a 24h `DATUM` filter, counting DISTINCT dumps (avoids the 512-byte buffer overflow that made the fallback fail). |
| 1.3.6   | SM37 redesigned: primary = corrected `TBTCO` read (trimmed columns + `STATUS='A'`/date WHERE, fixes 512-byte buffer overflow and now counts aborted jobs); fallback = XBP `BAPI_XBP_JOB_SELECT` over a stateful `JCoContext` XMI session; dropped dead `BAPI_XBP_GET_JOB_LIST`/`BP_JOB_SELECT` and the bogus `'C'` status. Both paths verified live (agree on count). |
| 1.3.5   | ST22 primary path now applies WARNING/CRITICAL thresholds (was hardcoded OK); removed dead `safeGet` helper; added `S4D.jcoDestination.template`; README aligned with implemented checks |
| 1.3.4   | ST22 now dynamically prints all fields from `ET_E2E_LOG` |
| 1.3.3   | Added `ET_E2E_LOG` support for `/SDF/GET_DUMP_LOG` |
| 1.3.2   | Added `BP_JOB_SELECT` as second primary option for SM37 |
| 1.3.1   | Standardized output format for all checks |
| 1.3.0   | Major refactor with consistent Primary/Fallback/Threshold/Status format |
| 1.2.x   | Corporate header, ASHOST fix, fallback improvements |
| 1.0.0   | Initial implementation |

---

**End of Document**