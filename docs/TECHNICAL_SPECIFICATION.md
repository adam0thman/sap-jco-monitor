# SAP JCo Monitor - Technical Specification

**Version:** 0.9  
**Date:** 2026-06-01  
**Author:** Hermes Agent (recreated from prior Java JCo design)

---

## 1. Overall Program Architecture

### 1.1 Design Goals
- Pure Java implementation using SAP Java Connector (JCo) 3.1
- Cron-friendly: single binary execution with clear exit codes
- No external dependencies beyond JCo
- Support for multiple SAP landscapes via `.jcoDestination` files
- Graceful degradation when certain RFCs are unavailable

### 1.2 Execution Flow
```
main()
  └── Load JCoDestination from name
  └── For each check:
        execute RFC
        evaluate thresholds
        update overall status (max of all checks)
  └── Print summary
  └── System.exit(overallStatus)
```

### 1.3 Exit Code Contract
| Code | Meaning     | Typical Use                     |
|------|-------------|---------------------------------|
| 0    | OK          | All checks within thresholds    |
| 1    | WARNING     | At least one check in warning   |
| 2    | CRITICAL    | At least one check in critical  |

### 1.4 Connection Handling
- Uses `JCoDestinationManager.getDestination(name)`
- Properties loaded from `destinations/<name>.jcoDestination`
- Connection pooling handled by JCo (peak_limit, pool_capacity)

### 1.5 Error Handling Strategy
- Missing RFC → skip check, log warning, treat as OK
- Connection failure → CRITICAL
- Table access authorization error → WARNING

---

## 2. Monitoring Checks - Detailed RFC & Table Specification

### 2.1 SM12 - Lock Entries (Enqueue)

**RFC Used:** `ENQUEUE_READ`

**Alternative (when ENQUEUE_READ unavailable):** `RFC_READ_TABLE` on table `ENQID` or `ENQUEUE`

**Key Tables & Fields:**
- Table: `ENQID` or internal enqueue tables
- Fields monitored:
  - `GNAME` (lock object name)
  - `GARG` (lock argument)
  - `MODE` (E = exclusive)
  - `UNAME` (user holding lock)
  - `TCODE` (transaction)

**Threshold Logic:**
- Critical: > 500 exclusive locks older than 30 minutes
- Warning: > 200 exclusive locks

**Implementation Note:**  
Many systems restrict `ENQUEUE_READ`. Fallback uses `RFC_READ_TABLE` with `QUERY_TABLE = 'ENQID'` and `DELIMITER`.

---

### 2.2 SM13 - Update Errors

**RFC Used:** `RFC_READ_TABLE`

**Primary Tables:**
- `VBHDR` (Update header)
- `VBMOD` (Update modules)

**Key Fields:**
- `VBHDR`:
  - `VBKEY` (update key)
  - `STATUS` (I=init, R=running, E=error, A=done)
  - `UPDDAT`, `UPDTIM`
  - `UNAME`
- `VBMOD`:
  - `VBKEY`
  - `MODNAME` (update function module)

**Threshold Logic:**
- Critical: Any update in status 'E' (error) in last 4 hours
- Warning: > 5 updates in status 'E' in last 24 hours

**Query Pattern:**
```abap
SELECT * FROM VBHDR 
WHERE STATUS = 'E' 
  AND UPDDAT >= (current_date - 1)
```

---

### 2.3 SMQ1 - Outbound Queue Status

**RFC Used:** `TRFC_QOUT_GET_STATUS` (if available) or `RFC_READ_TABLE`

**Primary Tables:**
- `TRFCQOUT` (qRFC outbound)
- `ARFCSSTATE` (tRFC state)

**Key Fields:**
- `TRFCQOUT`:
  - `QNAME` (queue name)
  - `QSTATE` (S=success, E=error, R=running)
  - `RETRIES`
  - `ARFCDEST`
- `ARFCSSTATE`:
  - `ARFCRETURNCODE`
  - `ARFCDEST`

**Threshold Logic:**
- Critical: Any queue with `QSTATE = 'E'` and `RETRIES > 3`
- Warning: Any queue with `RETRIES > 10`

---

### 2.4 SM51 - Application Server Status

**RFC Used:** `TH_SERVER_LIST` or `RFC_SYSTEM_INFO` + `RFC_GET_SYSTEM_INFO`

**Alternative:** `RFC_READ_TABLE` on `ALGLOBTREE` or use `BAPI_USER_GET_SYSTEMS`

**Key Information Retrieved:**
- Server name
- Instance number
- Status (active/inactive)
- Dialog response time (from `SMLG` or `ALGLOBTREE`)

**Threshold Logic:**
- Critical: Any production server in status inactive
- Warning: Response time > 2000ms on any server

---

### 2.5 SM37 - Background Job Failures

**RFC Used:** `BAPI_XBP_JOB_SELECT` (preferred) or `RFC_READ_TABLE` on `TBTCO` + `TBTCP`

**Primary Tables:**
- `TBTCO` (Job header)
- `TBTCP` (Job steps)

**Key Fields:**
- `TBTCO`:
  - `JOBNAME`
  - `JOBCOUNT`
  - `STATUS` (A=active, F=finished, C=cancelled, R=ready)
  - `SDLSTRTDT`, `SDLSTRTTM`
  - `SDLUNAME`

**Threshold Logic:**
- Critical: Any cancelled job (`STATUS = 'C'`) belonging to critical job names in last 24h
- Warning: > 3 cancelled jobs in last 24h

**Recommended Filter:**
Only monitor jobs where `JOBNAME` like `Z*` or specific critical jobs.

---

### 2.6 ST22 - Short Dumps

**RFC Used:** `RFC_READ_TABLE`

**Primary Table:** `SNAP`

**Key Fields:**
- `SNAP`:
  - `DATUM`, `UZEIT`
  - `AHOST` (application server)
  - `UNAME`
  - `MANDT`
  - `FLIST` (contains error text, often truncated)
  - `KWORD1` (error category, e.g. `MESSAGE_TYPE_X`, `DBIF_RSQL_SQL_ERROR`)

**Threshold Logic:**
- Critical: > 50 dumps in last 24 hours OR any `MESSAGE_TYPE_X` or `SYSTEM_CORE_DUMPED`
- Warning: > 10 dumps in last 24 hours

**Common Query:**
```sql
SELECT COUNT(*) FROM SNAP 
WHERE DATUM = current_date 
  AND (KWORD1 LIKE '%ERROR%' OR KWORD1 LIKE '%DUMP%')
```

---

### 2.7 SMLG - Logon Group Response Time

**RFC Used:** `BAPI_SMLG_GET` or `RFC_READ_TABLE` on `ALGLOBTREE` + `SMLG`

**Primary Tables:**
- `SMLG` (Logon group configuration)
- `ALGLOBTREE` (Global tree for response times)

**Key Fields:**
- Response time (ms)
- Queue length
- Users logged on per group

**Threshold Logic:**
- Critical: Average response time > 3000ms for any production logon group
- Warning: Average response time > 1500ms

---

### 2.8 DB02 - Database Space & Log Sync

**RFC Used:** Custom or `RFC_READ_TABLE` on DB-specific tables

**Common Tables (varies by DB):**
- HANA: `M_DISK_USAGE`, `M_BACKUP`
- Oracle: `DBA_DATA_FILES`, `V$LOG`
- AnyDB: `DB6` tables or `DB02` transaction data via RFC

**Key Metrics:**
- Tablespace usage %
- Log switch frequency
- Last successful backup timestamp

**Threshold Logic:**
- Critical: Any tablespace > 90% full
- Warning: Any tablespace > 80% full

**Note:** This check often requires a small custom RFC or use of `DB_GET_TABLES` style functions.

---

## 3. Implementation Recommendations

### 3.1 Recommended RFC Access Pattern
Most checks should prefer these patterns (in order):

1. Dedicated BAPI when available (`BAPI_XBP_*`, `ENQUEUE_READ`)
2. `RFC_READ_TABLE` with proper `FIELDNAME` and `OPTIONS` parameters
3. `RFC_GET_TABLE_ENTRIES` (newer systems)

### 3.2 Security / Authorization
Required authorizations:
- `S_RFC` for the used function groups
- `S_TABU_DIS` or `S_TABU_NAM` for table access
- `S_BTCH_JOB` for job monitoring

### 3.3 Future Enhancements
- Add JSON output mode (`--format json`)
- Add email / webhook alerting
- Add configuration file for thresholds per destination
- Add parallel execution of checks

---

**End of Technical Specification**