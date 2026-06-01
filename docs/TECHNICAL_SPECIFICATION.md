# SAP JCo Monitor - Technical Specification

**Version:** 1.2.1  
**Date:** 2026-06-01  
**Author:** Hermes Agent

---

## 1. Overall Program Architecture

### 1.1 Design Goals
- Pure Java implementation using SAP Java Connector (JCo) 3.1
- Cron-friendly: single binary execution with clear exit codes (0=OK, 1=Warning, 2=Critical)
- No external dependencies beyond JCo
- Support for multiple SAP landscapes via `.jcoDestination` files
- Graceful degradation when certain RFCs are unavailable
- Clear verbose/debug output showing primary vs fallback methods

### 1.2 Execution Flow
```
main()
  └── Load JCoDestination from name
  └── Print corporate header (version, timestamp, ASHOST, client, user)
  └── For each check:
        try primary BAPI/RFC
        if not available → fallback to RFC_READ_TABLE
        evaluate thresholds
        update overall status
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
- `ASHOST` is read directly from `jco.client.ashost` in the properties file

---

## 2. Monitoring Checks - Final Implementation

### 2.1 SM12 - Lock Entries

**Primary:** `ENQUEUE_READ`  
**Fallback:** `RFC_READ_TABLE` on `ENQID`

**Thresholds:**
- Warning: > 5000 active locks

**Implementation Note:** Code clearly logs when it falls back to `RFC_READ_TABLE`.

---

### 2.2 SM13 - Update Records

**Method:** `RFC_READ_TABLE` on `VBMOD`

**Thresholds:** Informational only (no hard limit)

---

### 2.3 SMQ1 - qRFC Queue Status

**Primary:** `TRFC_QOUT_GET_STATUS`  
**Fallback:** `RFC_READ_TABLE` on `TRFCQOUT`

**Thresholds:** Informational only

**Implementation Note:** Code explicitly states when primary is unavailable and fallback is used.

---

### 2.4 SM51 - Application Servers

**Method:** `TH_SERVER_LIST`

**Thresholds:**
- Critical: 0 active servers

---

### 2.5 SM37 - Background Jobs (Last 24h)

**Primary:** `BAPI_XBP_GET_JOB_LIST`  
**Fallback:** `RFC_READ_TABLE` on `TBTCO`

**Thresholds:**
- Warning: > 10 failed/cancelled jobs in last 24h

**Implementation Note:** Code logs the fallback clearly when BAPI is not available.

---

### 2.6 ST22 - Short Dumps

**Preferred (when available):** `/SDF/GET_DUMP_LOG`  
**Fallback:** `RFC_READ_TABLE` on `SNAP`

**Key Fields (SNAP):**
- `DATUM`, `UZEIT`
- `AHOST`, `UNAME`, `MANDT`
- `KWORD1` (error category)

**Thresholds:**
- Warning: > 10 dumps in last 24 hours
- Critical: > 50 dumps OR any `MESSAGE_TYPE_X` / `SYSTEM_CORE_DUMPED`

**Note:** `/SDF/GET_DUMP_LOG` is a more structured and preferred function module on many systems (especially SolMan-enabled landscapes). Current implementation uses `RFC_READ_TABLE` on `SNAP` as fallback.

---

### 2.7 SMLG - Logon Groups

**Method:** `SMLG_GET_DEFINED_GROUPS`

**Thresholds:** Informational only

---

## 3. Version History

| Version | Changes |
|---------|---------|
| 1.2.1   | Added corporate header with version, timestamp, and real `ASHOST` from `.jcoDestination` |
| 1.2.0   | Improved fallback logic with clear verbose messaging for all checks |
| 1.1.0   | Added proper `ASHOST` handling and corporate output format |
| 1.0.0   | Initial implementation matching technical specification |

---

**End of Document**