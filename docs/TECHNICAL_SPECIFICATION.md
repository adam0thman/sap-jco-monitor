# SAP JCo Monitor - Technical Specification

**Version:** 1.3.0  
**Date:** 2026-06-01  
**Author:** Hermes Agent

---

## 1. Overall Program Architecture

### 1.1 Design Goals
- Pure Java implementation using SAP Java Connector (JCo) 3.1
- Cron-friendly with clear exit codes (0=OK, 1=Warning, 2=Critical)
- Standardized output format across all checks
- Graceful degradation with clear primary vs fallback messaging
- Support for multiple landscapes via `.jcoDestination` files

### 1.2 Standardized Check Output Format

Every monitoring check follows this consistent structure:

```
>>> [XXX] Check Name
    Primary Method         : <Primary BAPI/RFC>
    Primary Method Result  : <Result or "Not available">
    Fallback Method        : <Fallback method or "Not needed">
    Fallback Method Result : <Result or "-">
    Threshold              : <Threshold description>
    Status                 : <OK / WARNING / CRITICAL / SKIPPED>
```

This format is applied uniformly to all checks (SM12, SM13, SMQ1, SM51, SM37, ST22, SMLG).

### 1.3 Exit Code Contract
| Code | Meaning     | Typical Use                     |
|------|-------------|---------------------------------|
| 0    | OK          | All checks within thresholds    |
| 1    | WARNING     | At least one check in warning   |
| 2    | CRITICAL    | At least one check in critical  |

---

## 2. Monitoring Checks - Final Implementation (v1.3.0)

### 2.1 SM12 - Lock Entries

**Primary:** `ENQUEUE_READ`  
**Fallback:** `RFC_READ_TABLE` on `ENQID`

**Threshold:** > 5000 = WARNING

---

### 2.2 SM13 - Update Records

**Primary:** `RFC_READ_TABLE` on `VBMOD`

**Threshold:** Informational only

---

### 2.3 SMQ1 - qRFC Queue Status

**Primary:** `TRFC_QOUT_GET_STATUS`  
**Fallback:** `RFC_READ_TABLE` on `TRFCQOUT`

**Threshold:** Informational only

---

### 2.4 SM51 - Application Servers

**Primary:** `TH_SERVER_LIST`

**Threshold:** >= 1 required

---

### 2.5 SM37 - Background Jobs (Last 24h)

**Primary:** `BAPI_XBP_GET_JOB_LIST`  
**Fallback:** `RFC_READ_TABLE` on `TBTCO`

**Threshold:** > 10 = WARNING

---

### 2.6 ST22 - Short Dumps

**Primary:** `/SDF/GET_DUMP_LOG`  
**Fallback:** `RFC_READ_TABLE` on `SNAP`

**Thresholds:**
- Warning: > 10 dumps
- Critical: > 50 dumps

---

### 2.7 SMLG - Logon Groups

**Primary:** `SMLG_GET_DEFINED_GROUPS`

**Threshold:** Informational only

---

## 3. Version History

| Version | Changes |
|---------|---------|
| 1.3.0   | Standardized output format for all checks (Primary / Fallback / Threshold / Status) |
| 1.2.3   | Enhanced ST22 to list actual dumps when using `/SDF/GET_DUMP_LOG` |
| 1.2.2   | Added proper fallback logic and verbose messaging for ST22 (`/SDF/GET_DUMP_LOG`) |
| 1.2.1   | Fixed ASHOST to read real value from `.jcoDestination` file |
| 1.2.0   | Corporate header with version, timestamp, and improved fallback messaging |
| 1.1.0   | Added clear primary vs fallback logging |
| 1.0.0   | Initial implementation |

---

**End of Document**