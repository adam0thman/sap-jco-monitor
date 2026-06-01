# SAP JCo Monitor - Technical Specification

**Version:** 1.3.5  
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

## 2. Monitoring Checks - Final Implementation (v1.3.5)

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
**Primary 1:** `BAPI_XBP_GET_JOB_LIST`  
**Primary 2:** `BP_JOB_SELECT`  
**Fallback:** `RFC_READ_TABLE` on `TBTCO`  
**Threshold:** > 10 = WARNING

### 2.6 ST22 - Short Dumps
**Primary:** `/SDF/GET_DUMP_LOG` (reads from `ET_E2E_LOG`)  
**Fallback:** `RFC_READ_TABLE` on `SNAP`  
**Thresholds:** > 10 = WARNING, > 50 = CRITICAL

**Note:** The script dynamically prints all available fields from `ET_E2E_LOG`.

### 2.7 SMLG - Logon Groups
**Primary:** `SMLG_GET_DEFINED_GROUPS`  
**Threshold:** Informational only

---

## 3. Version History

| Version | Changes |
|---------|---------|
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